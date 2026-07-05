/*
 * ble_adv_hci - raw BLE advertising injector for the Ava ble-adv-proxy mod.
 *
 * Mirrors ha-ble-adv BluetoothHCIAdapter._hci_advertise / _mgmt_advertise and
 * esphome-ble_adv_proxy esp_ble_gap_config_adv_data_raw().
 *
 * Usage:  ble_adv_hci <hci_index> <auto|hci|mgmt> <duration_ms> <hex_pdu>
 *
 * On Android the Bluetooth stack usually owns HCI LE advertising (status 0x0C);
 * auto mode therefore tries MGMT Add Advertising first, then raw HCI.
 */
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>
#include <errno.h>
#include <poll.h>
#include <stdint.h>
#include <sys/socket.h>
#include <time.h>

#ifndef AF_BLUETOOTH
#define AF_BLUETOOTH 31
#endif
#define BTPROTO_HCI 1
#define SOL_HCI 0
#define HCI_FILTER 2
#define HCI_CHANNEL_RAW 0
#define HCI_CHANNEL_CONTROL 3
#define HCI_DEV_NONE 0xffff

#define HCI_COMMAND_PKT 0x01
#define HCI_EVENT_PKT 0x04
#define EVT_CMD_COMPLETE 0x0E
#define EVT_CMD_STATUS 0x0F

#define OGF_LE 0x08
#define OCF_SET_ADV_PARAMS 0x0006
#define OCF_SET_ADV_DATA 0x0008
#define OCF_SET_ADV_ENABLE 0x000A

#define MGMT_OP_ADD_ADVERTISING 0x003E
#define MGMT_OP_REMOVE_ADVERTISING 0x003F
#define MGMT_EV_CMD_COMPLETE 0x0001
#define MGMT_EV_CMD_STATUS 0x0002

#define HCI_STATUS_DISALLOWED 0x0C
#define ADV_LEN 31
#define ADV_INST 1
#define MIN_ADV_UNITS 0x20

struct sockaddr_hci {
    unsigned short hci_family;
    unsigned short hci_dev;
    unsigned short hci_channel;
};

struct hci_filter {
    uint32_t type_mask;
    uint32_t event_mask[2];
    uint16_t opcode;
};

static int g_verbose = 0;

static long now_ms(void) {
    struct timespec ts;
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return ts.tv_sec * 1000L + ts.tv_nsec / 1000000L;
}

static int hex_to_bytes(const char *hex, uint8_t *out, int max) {
    int n = 0;
    for (const char *p = hex; p[0] && p[1] && n < max; p += 2) {
        char b[3] = {p[0], p[1], 0};
        char *end = NULL;
        long v = strtol(b, &end, 16);
        if (end != b + 2) return -1;
        out[n++] = (uint8_t) v;
    }
    return n;
}

/* ha-ble-adv: patched_data = data + zeros to 31 bytes */
static int pad_pdu(const uint8_t *in, int in_len, uint8_t *out) {
    int n = in_len < ADV_LEN ? in_len : ADV_LEN;
    memset(out, 0, ADV_LEN);
    if (n > 0) memcpy(out, in, n);
    return ADV_LEN;
}

static int min_adv_units(int duration_ms) {
    int scaled = (int) (duration_ms * 1.6f);
    if (scaled < MIN_ADV_UNITS) scaled = MIN_ADV_UNITS;
    return scaled;
}

static int hci_cmd(int fd, uint16_t opcode, const uint8_t *params, int plen) {
    uint8_t pkt[4 + 259];
    pkt[0] = HCI_COMMAND_PKT;
    pkt[1] = opcode & 0xFF;
    pkt[2] = (opcode >> 8) & 0xFF;
    pkt[3] = (uint8_t) plen;
    if (plen > 0) memcpy(pkt + 4, params, plen);
    if (write(fd, pkt, 4 + plen) < 0) return 0x101;

    long deadline = now_ms() + 1500;
    uint8_t buf[300];
    while (now_ms() < deadline) {
        struct pollfd pfd = {fd, POLLIN, 0};
        int pr = poll(&pfd, 1, (int) (deadline - now_ms()));
        if (pr <= 0) break;
        int r = read(fd, buf, sizeof(buf));
        if (r < 3) continue;
        if (buf[0] != HCI_EVENT_PKT) continue;
        if (buf[1] == EVT_CMD_COMPLETE && r >= 6) {
            uint16_t op = buf[4] | (buf[5] << 8);
            if (op == opcode) return (r >= 7) ? buf[6] : 0;
        } else if (buf[1] == EVT_CMD_STATUS && r >= 6) {
            uint16_t op = buf[5] | (buf[6] << 8);
            if (op == opcode) return buf[3];
        }
    }
    return 0x102;
}

static int open_hci_raw(int dev) {
    int fd = socket(AF_BLUETOOTH, SOCK_RAW, BTPROTO_HCI);
    if (fd < 0) return -1;
    struct hci_filter flt;
    memset(&flt, 0, sizeof(flt));
    flt.type_mask = 1u << HCI_EVENT_PKT;
    flt.event_mask[0] = (1u << EVT_CMD_COMPLETE) | (1u << EVT_CMD_STATUS);
    setsockopt(fd, SOL_HCI, HCI_FILTER, &flt, sizeof(flt));
    struct sockaddr_hci addr;
    memset(&addr, 0, sizeof(addr));
    addr.hci_family = AF_BLUETOOTH;
    addr.hci_dev = (unsigned short) dev;
    addr.hci_channel = HCI_CHANNEL_RAW;
    if (bind(fd, (struct sockaddr *) &addr, sizeof(addr)) < 0) {
        close(fd);
        return -1;
    }
    return fd;
}

static int try_hci(int dev, int duration_ms, const uint8_t *padded) {
    int fd = open_hci_raw(dev);
    if (fd < 0) {
        if (g_verbose) fprintf(stderr, "hci open/bind failed: %s\n", strerror(errno));
        return -1;
    }

    int adv_units = min_adv_units(duration_ms);
    uint8_t params[15] = {
        (uint8_t) (adv_units & 0xFF), (uint8_t) ((adv_units >> 8) & 0xFF),
        (uint8_t) (adv_units & 0xFF), (uint8_t) ((adv_units >> 8) & 0xFF),
        0x00, 0x00, 0x00, 0, 0, 0, 0, 0, 0, 0x07, 0x00
    };

    (void) hci_cmd(fd, OCF_SET_ADV_ENABLE | (OGF_LE << 10), (uint8_t[]) {0x00}, 1);
    hci_cmd(fd, OCF_SET_ADV_PARAMS | (OGF_LE << 10), params, sizeof(params));

    /* ha-ble-adv: bytearray([len(data), *data]) with len=31 after padding */
    uint8_t dpkt[1 + ADV_LEN];
    dpkt[0] = ADV_LEN;
    memcpy(dpkt + 1, padded, ADV_LEN);
    hci_cmd(fd, OCF_SET_ADV_DATA | (OGF_LE << 10), dpkt, sizeof(dpkt));

    int en = hci_cmd(fd, OCF_SET_ADV_ENABLE | (OGF_LE << 10), (uint8_t[]) {0x01}, 1);
    if (en == HCI_STATUS_DISALLOWED) {
        close(fd);
        return HCI_STATUS_DISALLOWED;
    }
    if (en != 0) {
        if (g_verbose) fprintf(stderr, "hci enable status 0x%02X\n", en);
        close(fd);
        return -2;
    }

    usleep((useconds_t) duration_ms * 1000);
    hci_cmd(fd, OCF_SET_ADV_ENABLE | (OGF_LE << 10), (uint8_t[]) {0x00}, 1);
    close(fd);
    return 0;
}

static int open_mgmt(void) {
    int fd = socket(AF_BLUETOOTH, SOCK_RAW, BTPROTO_HCI);
    if (fd < 0) return -1;
    struct sockaddr_hci addr;
    memset(&addr, 0, sizeof(addr));
    addr.hci_family = AF_BLUETOOTH;
    addr.hci_dev = HCI_DEV_NONE;
    addr.hci_channel = HCI_CHANNEL_CONTROL;
    if (bind(fd, (struct sockaddr *) &addr, sizeof(addr)) < 0) {
        close(fd);
        return -1;
    }
    return fd;
}

static int mgmt_cmd(int fd, uint16_t op, uint16_t index, const uint8_t *params, int plen) {
    uint8_t pkt[6 + 259];
    pkt[0] = op & 0xFF;
    pkt[1] = (op >> 8) & 0xFF;
    pkt[2] = index & 0xFF;
    pkt[3] = (index >> 8) & 0xFF;
    pkt[4] = plen & 0xFF;
    pkt[5] = (plen >> 8) & 0xFF;
    if (plen > 0) memcpy(pkt + 6, params, plen);
    if (write(fd, pkt, 6 + plen) < 0) return 0x101;

    long deadline = now_ms() + 2500;
    uint8_t buf[512];
    while (now_ms() < deadline) {
        struct pollfd pfd = {fd, POLLIN, 0};
        int pr = poll(&pfd, 1, (int) (deadline - now_ms()));
        if (pr <= 0) break;
        int r = read(fd, buf, sizeof(buf));
        if (r < 9) continue;
        uint16_t ev = buf[0] | (buf[1] << 8);
        uint16_t cop = buf[6] | (buf[7] << 8);
        if (ev == MGMT_EV_CMD_COMPLETE && cop == op) return buf[8];
        if (ev == MGMT_EV_CMD_STATUS && cop == op) return buf[8];
    }
    return 0x102;
}

/* ha-ble-adv BluetoothHCIAdapter.FAKE_ADV padded to 31 bytes. */
static void fake_adv_padded(uint8_t *out) {
    memset(out, 0, ADV_LEN);
    out[0] = 0x1D;
    out[1] = 0xFF;
    out[2] = 0xFF;
    out[3] = 0xFF;
}

static int try_mgmt(int dev, int duration_ms, const uint8_t *padded) {
    int fd = open_mgmt();
    if (fd < 0) {
        if (g_verbose) fprintf(stderr, "mgmt open/bind failed: %s\n", strerror(errno));
        return -1;
    }

    /* struct.pack("<BIHHBB{data_len}B", ADV_INST, 0, 0, 0, data_len, 0, *data) */
    uint8_t params[11 + ADV_LEN];
    int i = 0;
    params[i++] = ADV_INST;
    params[i++] = 0; params[i++] = 0; params[i++] = 0; params[i++] = 0;
    params[i++] = 0; params[i++] = 0;
    params[i++] = 0; params[i++] = 0;
    params[i++] = ADV_LEN;
    params[i++] = 0x00;
    memcpy(params + i, padded, ADV_LEN);
    i += ADV_LEN;

    int st = mgmt_cmd(fd, MGMT_OP_ADD_ADVERTISING, (uint16_t) dev, params, i);
    if (st != 0) {
        if (g_verbose) fprintf(stderr, "mgmt add adv status 0x%02X\n", st);
        close(fd);
        return -2;
    }
    usleep((useconds_t) duration_ms * 1000);
    uint8_t rm[1] = {ADV_INST};
    mgmt_cmd(fd, MGMT_OP_REMOVE_ADVERTISING, (uint16_t) dev, rm, 1);
    close(fd);
    return 0;
}

/* Detect transport, then emit a real short FAKE_ADV burst so the stack path is proven on-air. */
static int probe_transport(int dev) {
    uint8_t padded[ADV_LEN];
    fake_adv_padded(padded);
    int probe_ms = 20;

    int hci_fd = open_hci_raw(dev);
    int hci_disallowed = 0;
    if (hci_fd >= 0) {
        int en = hci_cmd(hci_fd, OCF_SET_ADV_ENABLE | (OGF_LE << 10), (uint8_t[]) {0x01}, 1);
        hci_cmd(hci_fd, OCF_SET_ADV_ENABLE | (OGF_LE << 10), (uint8_t[]) {0x00}, 1);
        close(hci_fd);
        if (en == HCI_STATUS_DISALLOWED) {
            hci_disallowed = 1;
        } else if (en == 0) {
            int r = try_hci(dev, probe_ms, padded);
            if (r == 0) {
                printf("PROBE transport=hci tx=ok\n");
                return 0;
            }
            printf("PROBE transport=hci tx=fail r=%d\n", r);
            return 1;
        }
    }

    if (hci_disallowed || hci_fd < 0) {
        int r = try_mgmt(dev, probe_ms, padded);
        if (r == 0) {
            printf("PROBE transport=mgmt tx=ok\n");
            return 0;
        }
        printf("PROBE transport=mgmt tx=fail r=%d\n", r);
        return 1;
    }

    printf("PROBE transport=none hci_open=%d mgmt_open=%d\n",
           hci_fd >= 0 ? 1 : 0, open_mgmt() >= 0 ? 1 : 0);
    return 1;
}

int main(int argc, char **argv) {
    if (getenv("BLE_ADV_HCI_VERBOSE")) g_verbose = 1;
    if (argc < 5) {
        fprintf(stderr, "usage: %s <hci_index> <auto|hci|mgmt|probe> <duration_ms> <hex_pdu>\n", argv[0]);
        printf("FAIL usage\n");
        return 2;
    }
    int dev = atoi(argv[1]);
    const char *mode = argv[2];
    if (strcmp(mode, "probe") == 0) {
        return probe_transport(dev) == 0 ? 0 : 1;
    }
    int duration = atoi(argv[3]);
    if (duration < 20) duration = 20;
    if (duration > 5000) duration = 5000;

    uint8_t data[64];
    int len = hex_to_bytes(argv[4], data, sizeof(data));
    if (len < 1) {
        printf("FAIL badhex\n");
        return 2;
    }

    uint8_t padded[ADV_LEN];
    pad_pdu(data, len, padded);

    int want_hci = (strcmp(mode, "hci") == 0) || (strcmp(mode, "auto") == 0);
    int want_mgmt = (strcmp(mode, "mgmt") == 0) || (strcmp(mode, "auto") == 0);

    /* Android: MGMT first in auto — stack owns HCI LE advertising. */
    if (want_mgmt && strcmp(mode, "auto") == 0) {
        int r = try_mgmt(dev, duration, padded);
        if (r == 0) {
            printf("OK mgmt\n");
            return 0;
        }
        want_mgmt = 0;
    }

    if (want_mgmt) {
        int r = try_mgmt(dev, duration, padded);
        if (r == 0) {
            printf("OK mgmt\n");
            return 0;
        }
        if (strcmp(mode, "mgmt") != 0) {
            /* fall through to hci */
        } else {
            printf("FAIL mgmt\n");
            return 1;
        }
    }

    if (want_hci) {
        int r = try_hci(dev, duration, padded);
        if (r == 0) {
            printf("OK hci\n");
            return 0;
        }
        if (r == HCI_STATUS_DISALLOWED && strcmp(mode, "auto") == 0) {
            r = try_mgmt(dev, duration, padded);
            if (r == 0) {
                printf("OK mgmt\n");
                return 0;
            }
        }
        if (strcmp(mode, "auto") != 0 || r != HCI_STATUS_DISALLOWED) {
            printf("FAIL hci\n");
            return 1;
        }
    }

    printf("FAIL unavailable\n");
    return 1;
}

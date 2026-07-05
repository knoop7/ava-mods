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
#include <stdarg.h>
#include "ble_adv_hci.h"

#ifdef BLE_ADV_JNI
static char g_out[512];

static void out_reset(void) {
    g_out[0] = '\0';
}

static void out_line(const char *fmt, ...) {
    char tmp[256];
    va_list ap;
    va_start(ap, fmt);
    vsnprintf(tmp, sizeof(tmp), fmt, ap);
    va_end(ap);
    size_t used = strlen(g_out);
    if (used + 1 < sizeof(g_out)) {
        strncat(g_out, tmp, sizeof(g_out) - used - 1);
    }
}

#define RESULT_PRINTF(...) out_line(__VA_ARGS__)
#else
#define RESULT_PRINTF(...) printf(__VA_ARGS__)
#endif

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
#define OCF_SET_SCAN_ENABLE 0x000C

#define MGMT_OP_READ_INDEX_LIST 0x0003
#define MGMT_OP_STOP_DISCOVERY 0x0024
#define MGMT_OP_ADD_ADVERTISING 0x003E
#define MGMT_OP_REMOVE_ADVERTISING 0x003F
#define MGMT_EV_CMD_COMPLETE 0x0001
#define MGMT_EV_CMD_STATUS 0x0002
#define MGMT_STATUS_INVALID_INDEX 0x11
#define MGMT_STATUS_BUSY 0x14

#define HCI_STATUS_DISALLOWED 0x0C
#define ADV_LEN 31
#define MIN_ADV_UNITS 0x20
#define MAX_MGMT_CTRL 8
#define MAX_ADV_INST 8

static int g_cached_ctrl = -1;
static int g_cached_inst = -1;

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

static void hci_disable_le_scan(int dev);
static void prep_controller_for_adv(int dev, int mgmt_fd,
                                    const uint16_t *ctrls, int nctrl);

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
    hci_disable_le_scan(dev);
    usleep(100000);

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

static int mgmt_read_controller_indices(int fd, uint16_t *out, int max_n) {
    uint8_t pkt[6] = {0x03, 0x00, 0xFF, 0xFF, 0x00, 0x00};
    if (write(fd, pkt, 6) < 0) {
        return 0;
    }

    long deadline = now_ms() + 1500;
    uint8_t buf[512];
    while (now_ms() < deadline) {
        struct pollfd pfd = {fd, POLLIN, 0};
        int pr = poll(&pfd, 1, (int) (deadline - now_ms()));
        if (pr <= 0) break;
        int r = read(fd, buf, sizeof(buf));
        if (r < 13) continue;
        uint16_t ev = buf[0] | (buf[1] << 8);
        uint16_t cop = buf[6] | (buf[7] << 8);
        if (ev == MGMT_EV_CMD_STATUS && cop == MGMT_OP_READ_INDEX_LIST) {
            return 0;
        }
        if (ev != MGMT_EV_CMD_COMPLETE || cop != MGMT_OP_READ_INDEX_LIST) {
            continue;
        }
        if (buf[8] != 0) {
            return 0;
        }
        int n = buf[9] | (buf[10] << 8);
        if (n <= 0) {
            return 0;
        }
        int copied = 0;
        for (int i = 0; i < n && copied < max_n; i++) {
            int off = 11 + i * 2;
            if (off + 1 >= r) break;
            out[copied++] = (uint16_t) (buf[off] | (buf[off + 1] << 8));
        }
        return copied;
    }
    return 0;
}

/* Release LE scan before MGMT Add Advertising — Java stopScan() is not enough on Android. */
static void hci_disable_le_scan(int dev) {
    int fd = open_hci_raw(dev);
    if (fd < 0) {
        if (g_verbose) fprintf(stderr, "hci disable scan: open failed\n");
        return;
    }
    uint8_t params[2] = {0x00, 0x00};
    int st = hci_cmd(fd, OCF_SET_SCAN_ENABLE | (OGF_LE << 10), params, sizeof(params));
    if (g_verbose) fprintf(stderr, "hci disable scan status=0x%02X\n", st & 0xFF);
    close(fd);
}

static void mgmt_stop_le_discovery(int fd, uint16_t ctrl) {
    static const uint8_t addr_types[] = {0x01, 0x02, 0x00};
    for (size_t i = 0; i < sizeof(addr_types) / sizeof(addr_types[0]); i++) {
        uint8_t t = addr_types[i];
        (void) mgmt_cmd(fd, MGMT_OP_STOP_DISCOVERY, ctrl, &t, 1);
    }
}

static void prep_controller_for_adv(int dev, int mgmt_fd,
                                    const uint16_t *ctrls, int nctrl) {
    hci_disable_le_scan(dev);
    for (int i = 0; i < nctrl; i++) {
        mgmt_stop_le_discovery(mgmt_fd, ctrls[i]);
    }
    usleep(200000);
}

static int mgmt_add_only(int fd, uint16_t ctrl, uint8_t inst, const uint8_t *padded) {
    uint8_t params[11 + ADV_LEN];
    int i = 0;
    params[i++] = inst;
    params[i++] = 0; params[i++] = 0; params[i++] = 0; params[i++] = 0;
    params[i++] = 0; params[i++] = 0;
    params[i++] = 0; params[i++] = 0;
    params[i++] = ADV_LEN;
    params[i++] = 0x00;
    memcpy(params + i, padded, ADV_LEN);
    i += ADV_LEN;
    return mgmt_cmd(fd, MGMT_OP_ADD_ADVERTISING, ctrl, params, i);
}

/* ha-ble-adv _mgmt_advertise: REMOVE inst -> ADD -> sleep -> REMOVE (no instance sweep). */
static int mgmt_one_shot(int fd, uint16_t ctrl, uint8_t inst,
                         int duration_ms, const uint8_t *padded, int *out_st) {
    uint8_t rm[1] = {inst};
    (void) mgmt_cmd(fd, MGMT_OP_REMOVE_ADVERTISING, ctrl, rm, 1);
    usleep(200000);

    int st = mgmt_add_only(fd, ctrl, inst, padded);
    if (out_st) {
        *out_st = st;
    }
    if (st != 0) {
        return -1;
    }
    usleep((useconds_t) duration_ms * 1000);
    mgmt_cmd(fd, MGMT_OP_REMOVE_ADVERTISING, ctrl, rm, 1);
    usleep(50000);
    return 0;
}

static int try_mgmt(int dev, int duration_ms, const uint8_t *padded) {
    const char *hint_ctrl = getenv("BLE_ADV_HINT_CTRL");
    const char *hint_inst = getenv("BLE_ADV_HINT_INST");
    if (hint_ctrl && hint_inst) {
        g_cached_ctrl = atoi(hint_ctrl);
        g_cached_inst = atoi(hint_inst);
    }

    int fd = open_mgmt();
    if (fd < 0) {
        RESULT_PRINTF("FAIL mgmt open errno=%d\n", errno);
        if (g_verbose) fprintf(stderr, "mgmt open/bind failed: %s\n", strerror(errno));
        return -1;
    }

    uint16_t ctrls[MAX_MGMT_CTRL];
    int nctrl = mgmt_read_controller_indices(fd, ctrls, MAX_MGMT_CTRL);
    if (nctrl <= 0) {
        ctrls[0] = (uint16_t) dev;
        nctrl = 1;
    }

    prep_controller_for_adv(dev, fd, ctrls, nctrl);

    int last_st = 0x102;
    static const int inst_order[] = {1, 0, 2, 3, 4, 5, 6, 7};

    if (g_cached_ctrl >= 0 && g_cached_inst >= 0) {
        for (int attempt = 0; attempt < 6; attempt++) {
            if (attempt > 0) {
                usleep(400000);
            }
            last_st = 0;
            if (mgmt_one_shot(fd, (uint16_t) g_cached_ctrl, (uint8_t) g_cached_inst,
                              duration_ms, padded, &last_st) == 0) {
                RESULT_PRINTF("OK mgmt ctrl=%d inst=%d\n", g_cached_ctrl, g_cached_inst);
                close(fd);
                return 0;
            }
            if (last_st != MGMT_STATUS_BUSY) {
                break;
            }
        }
        g_cached_ctrl = -1;
        g_cached_inst = -1;
    }

    for (int ci = 0; ci < nctrl; ci++) {
        uint16_t ctrl = ctrls[ci];
        for (size_t oi = 0; oi < sizeof(inst_order) / sizeof(inst_order[0]); oi++) {
            int inst = inst_order[oi];
            for (int attempt = 0; attempt < 6; attempt++) {
                if (attempt > 0) {
                    usleep(400000);
                }
                last_st = 0;
                if (mgmt_one_shot(fd, ctrl, (uint8_t) inst, duration_ms, padded, &last_st) == 0) {
                    g_cached_ctrl = (int) ctrl;
                    g_cached_inst = inst;
                    RESULT_PRINTF("OK mgmt ctrl=%d inst=%d\n", g_cached_ctrl, g_cached_inst);
                    close(fd);
                    return 0;
                }
                if (last_st == MGMT_STATUS_BUSY) {
                    continue;
                }
                break;
            }
            if (g_verbose) {
                fprintf(stderr, "TRY ctrl=%u inst=%d st=0x%02X\n", ctrl, inst, last_st & 0xFF);
            }
        }
    }

    RESULT_PRINTF("FAIL mgmt status=0x%02X nctrl=%d\n", last_st & 0xFF, nctrl);
    if (g_verbose) fprintf(stderr, "mgmt exhausted, last=0x%02X\n", last_st);
    close(fd);
    return -2;
}

/* ha-ble-adv BluetoothHCIAdapter.FAKE_ADV padded to 31 bytes. */
static void fake_adv_padded(uint8_t *out) {
    memset(out, 0, ADV_LEN);
    out[0] = 0x1D;
    out[1] = 0xFF;
    out[2] = 0xFF;
    out[3] = 0xFF;
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
                RESULT_PRINTF("PROBE transport=hci tx=ok\n");
                return 0;
            }
            RESULT_PRINTF("PROBE transport=hci tx=fail r=%d\n", r);
            return 1;
        }
    }

    if (hci_disallowed || hci_fd < 0) {
        int r = try_mgmt(dev, probe_ms, padded);
        if (r == 0) {
            RESULT_PRINTF("PROBE transport=mgmt tx=ok\n");
            return 0;
        }
        RESULT_PRINTF("PROBE transport=mgmt tx=fail r=%d\n", r);
        return 1;
    }

    RESULT_PRINTF("PROBE transport=none hci_open=%d mgmt_open=%d\n",
           hci_fd >= 0 ? 1 : 0, open_mgmt() >= 0 ? 1 : 0);
    return 1;
}

static int run_mode(int dev, const char *mode, int duration_ms, const uint8_t *padded) {
    if (strcmp(mode, "probe") == 0) {
        return probe_transport(dev) == 0 ? 0 : -1;
    }
    if (strcmp(mode, "auto") == 0 || strcmp(mode, "mgmt") == 0) {
        return try_mgmt(dev, duration_ms, padded);
    }
    if (strcmp(mode, "hci") == 0) {
        int r = try_hci(dev, duration_ms, padded);
        if (r == 0) {
            RESULT_PRINTF("OK hci\n");
            return 0;
        }
        if (r == HCI_STATUS_DISALLOWED) {
            r = try_mgmt(dev, duration_ms, padded);
            if (r == 0) {
                return 0;
            }
        }
        RESULT_PRINTF("FAIL hci\n");
        return -1;
    }
    RESULT_PRINTF("FAIL unavailable\n");
    return -1;
}

int ble_adv_execute(int dev, const char *mode, int duration_ms,
                    const uint8_t *data, int data_len,
                    char *out, int out_cap) {
    int rc = -1;
#ifdef BLE_ADV_JNI
    out_reset();
#endif
    if (out != NULL && out_cap > 0) {
        out[0] = '\0';
    }
    if (mode == NULL || data == NULL || data_len < 1) {
        RESULT_PRINTF("FAIL bad_args\n");
        goto finish;
    }
    if (duration_ms < 20) {
        duration_ms = 20;
    }
    if (duration_ms > 5000) {
        duration_ms = 5000;
    }
    uint8_t padded[ADV_LEN];
    pad_pdu(data, data_len, padded);
    rc = run_mode(dev, mode, duration_ms, padded);

finish:
#ifdef BLE_ADV_JNI
    if (out != NULL && out_cap > 0) {
        if (g_out[0] != '\0') {
            strncpy(out, g_out, (size_t) out_cap - 1);
            out[out_cap - 1] = '\0';
        }
    }
#endif
    return rc == 0 ? 0 : -1;
}

#ifdef BLE_ADV_STANDALONE
int main(int argc, char **argv) {
    if (getenv("BLE_ADV_HCI_VERBOSE")) g_verbose = 1;
    if (argc < 5) {
        fprintf(stderr, "usage: %s <hci_index> <auto|hci|mgmt|probe> <duration_ms> <hex_pdu>\n", argv[0]);
        RESULT_PRINTF("FAIL usage\n");
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
        RESULT_PRINTF("FAIL badhex\n");
        return 2;
    }

    char out[512];
    memset(out, 0, sizeof(out));
    int rc = ble_adv_execute(dev, mode, duration, data, len, out, sizeof(out));
    if (rc == 0) {
        if (out[0] != '\0') {
            printf("%s", out);
        }
        return 0;
    }
    if (out[0] != '\0') {
        printf("%s", out);
    }
    return 1;
}
#endif

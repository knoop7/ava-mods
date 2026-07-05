#ifndef BLE_ADV_HCI_H
#define BLE_ADV_HCI_H

#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

/**
 * Run transmit/probe. Writes human-readable status into out (e.g. "OK mgmt ctrl=0 inst=1").
 * Returns 0 on success.
 */
int ble_adv_execute(int dev, const char *mode, int duration_ms,
                    const uint8_t *data, int data_len,
                    char *out, int out_cap);

#ifdef __cplusplus
}
#endif

#endif

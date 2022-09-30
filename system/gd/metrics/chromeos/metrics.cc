/*
 * Copyright 2022 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
#define LOG_TAG "BluetoothMetrics"

#include "gd/metrics/metrics.h"

#include <metrics/structured_events.h>

#include "common/time_util.h"
#include "gd/metrics/chromeos/metrics_allowlist.h"
#include "gd/metrics/chromeos/metrics_event.h"
#include "gd/metrics/utils.h"
#include "gd/os/log.h"

namespace bluetooth {
namespace metrics {

static constexpr uint32_t DEVICE_MAJOR_CLASS_MASK = 0x1F00;
static constexpr uint32_t DEVICE_MAJOR_CLASS_BIT_OFFSET = 8;
static constexpr uint32_t DEVICE_CATEGORY_MASK = 0xFFC0;
static constexpr uint32_t DEVICE_CATEGORY_BIT_OFFSET = 6;

void LogMetricsAdapterStateChanged(uint32_t state) {
  int64_t adapter_state;
  int64_t boot_time;
  std::string boot_id;

  if (!GetBootId(&boot_id)) return;

  adapter_state = (int64_t)ToAdapterState(state);
  boot_time = bluetooth::common::time_get_os_boottime_us();

  LOG_DEBUG("AdapterStateChanged: %s, %d, %d", boot_id.c_str(), boot_time, adapter_state);

  ::metrics::structured::events::bluetooth::BluetoothAdapterStateChanged()
      .SetBootId(boot_id)
      .SetSystemTime(boot_time)
      .SetIsFloss(true)
      .SetAdapterState(adapter_state)
      .Record();
}

void LogMetricsBondCreateAttempt(RawAddress* addr, uint32_t device_type) {
  int64_t boot_time;
  std::string addr_string;
  std::string boot_id;

  if (!GetBootId(&boot_id)) return;

  addr_string = addr->ToString();
  boot_time = bluetooth::common::time_get_os_boottime_us();

  LOG_DEBUG(
      "PairingStateChanged: %s, %d, %s, %d, %d",
      boot_id.c_str(),
      boot_time,
      addr_string.c_str(),
      device_type,
      PairingState::PAIR_STARTING);

  ::metrics::structured::events::bluetooth::BluetoothPairingStateChanged()
      .SetBootId(boot_id)
      .SetSystemTime(boot_time)
      .SetDeviceId(addr_string)
      .SetDeviceType(device_type)
      .SetPairingState((int64_t)PairingState::PAIR_STARTING)
      .Record();
}

void LogMetricsBondStateChanged(
    RawAddress* addr, uint32_t device_type, uint32_t status, uint32_t bond_state, int32_t fail_reason) {
  int64_t boot_time;
  PairingState pairing_state;
  std::string addr_string;
  std::string boot_id;

  if (!GetBootId(&boot_id)) return;

  addr_string = addr->ToString();
  boot_time = bluetooth::common::time_get_os_boottime_us();
  pairing_state = ToPairingState(status, bond_state, fail_reason);

  // Ignore the start of pairing event as its logged separated above.
  if (pairing_state == PairingState::PAIR_STARTING) return;

  LOG_DEBUG(
      "PairingStateChanged: %s, %d, %s, %d, %d",
      boot_id.c_str(),
      boot_time,
      addr_string.c_str(),
      device_type,
      pairing_state);

  ::metrics::structured::events::bluetooth::BluetoothPairingStateChanged()
      .SetBootId(boot_id)
      .SetSystemTime(boot_time)
      .SetDeviceId(addr_string)
      .SetDeviceType(device_type)
      .SetPairingState((int64_t)pairing_state)
      .Record();
}

void LogMetricsDeviceInfoReport(
    RawAddress* addr,
    uint32_t device_type,
    uint32_t class_of_device,
    uint32_t appearance,
    uint32_t vendor_id,
    uint32_t vendor_id_src,
    uint32_t product_id,
    uint32_t version) {
  int64_t boot_time;
  std::string addr_string;
  std::string boot_id;
  uint32_t major_class;
  uint32_t category;

  if (!GetBootId(&boot_id)) return;

  addr_string = addr->ToString();
  boot_time = bluetooth::common::time_get_os_boottime_us();

  major_class = (class_of_device & DEVICE_MAJOR_CLASS_MASK) >> DEVICE_MAJOR_CLASS_BIT_OFFSET;
  category = (appearance & DEVICE_CATEGORY_MASK) >> DEVICE_CATEGORY_BIT_OFFSET;

  LOG_DEBUG(
      "DeviceInfoReport %s %d %s %d %d %d %d %d %d %d",
      boot_id.c_str(),
      boot_time,
      addr_string.c_str(),
      device_type,
      major_class,
      category,
      vendor_id,
      vendor_id_src,
      product_id,
      version);

  if (!IsDeviceInfoInAllowlist(vendor_id_src, vendor_id, product_id)) {
    vendor_id_src = 0;
    vendor_id = 0;
    product_id = 0;
    version = 0;
  }

  ::metrics::structured::events::bluetooth::BluetoothDeviceInfoReport()
      .SetBootId(boot_id)
      .SetSystemTime(boot_time)
      .SetDeviceId(addr_string)
      .SetDeviceType(device_type)
      .SetDeviceClass(major_class)
      .SetDeviceCategory(category)
      .SetVendorId(vendor_id)
      .SetVendorIdSource(vendor_id_src)
      .SetProductId(product_id)
      .SetProductVersion(version)
      .Record();
}

void LogMetricsProfileConnectionAttempt(RawAddress* addr, uint32_t intent, uint32_t profile) {}

void LogMetricsProfileConnectionStateChanged(
    RawAddress* addr, uint32_t intent, uint32_t profile, uint32_t status, uint32_t state) {}

}  // namespace metrics
}  // namespace bluetooth

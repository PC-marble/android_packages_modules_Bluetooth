/******************************************************************************
 *
 *  Copyright (c) 2014 The Android Open Source Project
 *  Copyright 2009-2012 Broadcom Corporation
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at:
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 *
 ******************************************************************************/

#ifndef BTIF_COMMON_H
#define BTIF_COMMON_H

#include <base/bind.h>
#include <base/location.h>
#include <hardware/bluetooth.h>
#include <stdlib.h>

#include <functional>

#include "abstract_message_loop.h"
#include "bta/include/bta_api.h"
#include "osi/include/log.h"
#include "osi/include/osi.h"
#include "stack/include/bt_hdr.h"
#include "types/raw_address.h"

/*******************************************************************************
 *  Constants & Macros
 ******************************************************************************/

#define ASSERTC(cond, msg, val)                                               \
  do {                                                                        \
    if (!(cond)) {                                                            \
      LOG_ERROR("### ASSERT : %s %s line %d %s (%d) ###", __FILE__, __func__, \
                __LINE__, (msg), (val));                                      \
    }                                                                         \
  } while (0)

/* Calculate start of event enumeration; id is top 8 bits of event */
#define BTIF_SIG_START(id) ((id) << 8)

/* For upstream the MSB bit is always SET */
#define BTIF_SIG_CB_BIT (0x8000)
#define BTIF_SIG_CB_START(id) (((id) << 8) | BTIF_SIG_CB_BIT)

/*
 * A memcpy(3) wrapper when copying memory that might not be aligned.
 *
 * On certain architectures, if the memcpy(3) arguments appear to be
 * pointing to aligned memory (e.g., struct pointers), the compiler might
 * generate optimized memcpy(3) code. However, if the original memory was not
 * aligned (e.g., because of incorrect "char *" to struct pointer casting),
 * the result code might trigger SIGBUS crash.
 *
 * As a short-term solution, we use the help of the maybe_non_aligned_memcpy()
 * macro to identify and fix such cases. In the future, we should fix the
 * problematic "char *" to struct pointer casting, and this macro itself should
 * be removed.
 */
#define maybe_non_aligned_memcpy(_a, _b, _c) \
  memcpy((void*)(_a), (void*)(_b), (_c))

/* BTIF sub-systems */
#define BTIF_CORE 0
#define BTIF_DM 1
#define BTIF_HFP 2
#define BTIF_AV 3
#define BTIF_PAN 4
#define BTIF_HF_CLIENT 5

#define HAL_CBACK(P_CB, P_CBACK, ...)                              \
  do {                                                             \
    if ((P_CB) && (P_CB)->P_CBACK) {                               \
      BTIF_TRACE_API("%s: HAL %s->%s", __func__, #P_CB, #P_CBACK); \
      (P_CB)->P_CBACK(__VA_ARGS__);                                \
    } else {                                                       \
      ASSERTC(0, "Callback is NULL", 0);                           \
    }                                                              \
  } while (0)

/**
 * BTIF events for requests that require context switch to btif task
 * on downstreams path
 */
enum {
  BTIF_DM_API_START = BTIF_SIG_START(BTIF_DM),
  BTIF_DM_ENABLE_SERVICE,
  BTIF_DM_DISABLE_SERVICE,
  /* add here */

  BTIF_HFP_API_START = BTIF_SIG_START(BTIF_HFP),
  /* add here */

  BTIF_AV_API_START = BTIF_SIG_START(BTIF_AV),
  /* add here */
};

/**
 * BTIF events for callbacks that require context switch to btif task
 * on upstream path - Typically these would be non-BTA events
 * that are generated by the BTIF layer.
 */
enum {
  BTIF_CORE_CB_START = BTIF_SIG_CB_START(BTIF_CORE),
  /* add here */

  BTIF_HFP_CB_START = BTIF_SIG_CB_START(BTIF_HFP),
  BTIF_HFP_CB_AUDIO_CONNECTING, /* HF AUDIO connect has been sent to BTA
                                   successfully */

  BTIF_PAN_CB_START = BTIF_SIG_CB_START(BTIF_PAN),
  BTIF_PAN_CB_DISCONNECTING, /* PAN Disconnect has been sent to BTA successfully
                                */

  BTIF_HF_CLIENT_CLIENT_CB_START = BTIF_SIG_CB_START(BTIF_HF_CLIENT),
  BTIF_HF_CLIENT_CB_AUDIO_CONNECTING, /* AUDIO connect has been sent to BTA
                                         successfully */
};

/*******************************************************************************
 *  Type definitions for callback functions
 ******************************************************************************/

typedef void(tBTIF_CBACK)(uint16_t event, char* p_param);
typedef void(tBTIF_COPY_CBACK)(uint16_t event, char* p_dest, char* p_src);

/*******************************************************************************
 *  Type definitions and return values
 ******************************************************************************/

/* this type handles all btif context switches between BTU and HAL */
typedef struct {
  BT_HDR_RIGID hdr;
  tBTIF_CBACK* p_cb; /* context switch callback */

  /* parameters passed to callback */
  uint16_t event;                          /* message event id */
  char __attribute__((aligned)) p_param[]; /* parameter area needs to be last */
} tBTIF_CONTEXT_SWITCH_CBACK;

/*******************************************************************************
 *  Functions
 ******************************************************************************/

extern bt_status_t do_in_jni_thread(base::OnceClosure task);
extern bt_status_t do_in_jni_thread(const base::Location& from_here,
                                    base::OnceClosure task);
extern bool is_on_jni_thread();
extern btbase::AbstractMessageLoop* get_jni_message_loop();

using BtJniClosure = std::function<void()>;
void post_on_bt_jni(BtJniClosure closure);

/**
 * This template wraps callback into callback that will be executed on jni
 * thread
 */
template <typename R, typename... Args>
base::Callback<R(Args...)> jni_thread_wrapper(const base::Location& from_here,
                                              base::Callback<R(Args...)> cb) {
  return base::Bind(
      [](const base::Location& from_here, base::Callback<R(Args...)> cb,
         Args... args) {
        do_in_jni_thread(from_here,
                         base::Bind(cb, std::forward<Args>(args)...));
      },
      from_here, std::move(cb));
}

tBTA_SERVICE_MASK btif_get_enabled_services_mask(void);
void btif_enable_service(tBTA_SERVICE_ID service_id);
void btif_disable_service(tBTA_SERVICE_ID service_id);
int btif_is_enabled(void);

/**
 * BTIF_Events
 */
void btif_enable_bluetooth_evt();
void btif_adapter_properties_evt(bt_status_t status, uint32_t num_props,
                                 bt_property_t* p_props);
void btif_remote_properties_evt(bt_status_t status, RawAddress* remote_addr,
                                uint32_t num_props, bt_property_t* p_props);

void bte_load_did_conf(const char* p_path);
void bte_main_init(void);

bt_status_t btif_transfer_context(tBTIF_CBACK* p_cback, uint16_t event,
                                  char* p_params, int param_len,
                                  tBTIF_COPY_CBACK* p_copy_cback);

void btif_init_ok();

void invoke_adapter_state_changed_cb(bt_state_t state);
void invoke_adapter_properties_cb(bt_status_t status, int num_properties,
                                  bt_property_t* properties);
void invoke_remote_device_properties_cb(bt_status_t status, RawAddress bd_addr,
                                        int num_properties,
                                        bt_property_t* properties);
void invoke_device_found_cb(int num_properties, bt_property_t* properties);
void invoke_discovery_state_changed_cb(bt_discovery_state_t state);
void invoke_pin_request_cb(RawAddress bd_addr, bt_bdname_t bd_name,
                           uint32_t cod, bool min_16_digit);
void invoke_ssp_request_cb(RawAddress bd_addr, bt_bdname_t bd_name,
                           uint32_t cod, bt_ssp_variant_t pairing_variant,
                           uint32_t pass_key);
void invoke_oob_data_request_cb(tBT_TRANSPORT t, bool valid, Octet16 c,
                                Octet16 r, RawAddress raw_address,
                                uint8_t address_type);
void invoke_bond_state_changed_cb(bt_status_t status, RawAddress bd_addr,
                                  bt_bond_state_t state, int fail_reason);
void invoke_address_consolidate_cb(RawAddress main_bd_addr,
                                   RawAddress secondary_bd_addr);
void invoke_acl_state_changed_cb(bt_status_t status, RawAddress bd_addr,
                                 bt_acl_state_t state, int transport_link_type,
                                 bt_hci_error_code_t hci_reason);
void invoke_thread_evt_cb(bt_cb_thread_evt event);
void invoke_le_test_mode_cb(bt_status_t status, uint16_t count);
void invoke_energy_info_cb(bt_activity_energy_info energy_info,
                           bt_uid_traffic_t* uid_data);
void invoke_link_quality_report_cb(
    uint64_t timestamp, int report_id, int rssi, int snr,
    int retransmission_count, int packets_not_receive_count,
    int negative_acknowledgement_count);

#endif /* BTIF_COMMON_H */
// 
// esp32c3 esp-idf v 5.5.1
//
// main.c
//
#include <stdio.h>
#include <stdlib.h>

#include <inttypes.h>

#include <string.h>

#include "driver/gpio.h"

#include "freertos/FreeRTOS.h"
#include "freertos/task.h"

#include "esp_log.h"
#include "esp_system.h"

#include "esp_spiffs.h"
#include "spi_flash_mmap.h"

#include "nvs_flash.h"
#include "esp_timer.h"

#include "lamp.h"
#include "webserver.h"

static const char *TAG = "AP lamp";

#define LED_PIN 8
#define led_on  0
#define led_off 1
static int freqLED = 10000;
static int led_state = 0;

static gpio_num_t BTN_1_PIN;
static gpio_num_t BTN_2_PIN;

typedef enum {
    EVENT_BUTTON_1,   // TTP223_1
    EVENT_BUTTON_2    // alice
} button_event_t;

static int btn2_last_level = -1;

static QueueHandle_t button_queue;

static webserver_ap_config_t ap_cfg = {0};


#define SECONDS_PER_TICK 10

/***********************/

static void IRAM_ATTR gpio_isr_handler(void* arg
) {
    BaseType_t higher_priority_task_woken = pdFALSE;
    uint32_t gpio_num = (uint32_t) arg;
    button_event_t event;

    if (gpio_num == BTN_1_PIN) {
        event = EVENT_BUTTON_1;
        xQueueSendFromISR(button_queue, &event, &higher_priority_task_woken);
    } else if (gpio_num == BTN_2_PIN) {
        event = EVENT_BUTTON_2;
        xQueueSendFromISR(button_queue, &event, &higher_priority_task_woken);
    }
    portYIELD_FROM_ISR(higher_priority_task_woken);
} // gpio_isr_handler

static void button_task(void* arg
) {
    const uint64_t hold_time_us = 2 * 1000000ULL; // 2 second
    const TickType_t poll_delay = pdMS_TO_TICKS( 50);
    button_event_t event;

    while (1) {
        if (xQueueReceive(button_queue, &event, portMAX_DELAY)) {
            if (event == EVENT_BUTTON_1) {
                ESP_LOGI(TAG, "Touch button1 detected (edge)");

                // Запомнить момент начала удержания (ISR был по фронту, но пин должен быть 1)
                uint64_t start = esp_timer_get_time();

                // Ожидаем либо отпускания, либо достижения времени удержания
                bool held_long_enough = false;
                while (1) {
                    int level = gpio_get_level(BTN_1_PIN);
                    if (level == 0) {
                        held_long_enough = false;
                        break;
                    }
                    if ((esp_timer_get_time() - start) >= hold_time_us) {
                        held_long_enough = true;
                        break;
                    }
                    vTaskDelay(poll_delay);
                }

                if (held_long_enough) {
                    ESP_LOGI(TAG, "Button held >= 3s, toggling lamp");
                    if (!LAMP_on) {
                        LAMP_turn_On();
                    } else {
                        LAMP_turn_Off();
                    }

                    // Ждём полного отпускания, чтобы не срабатывать повторно на одном удержании
                    while (gpio_get_level(BTN_1_PIN) == 1) {
                        vTaskDelay(poll_delay);
                    }
                    vTaskDelay(pdMS_TO_TICKS(50)); // (debounce)
                } else {
                    ESP_LOGI(TAG, "Button released before 3s - ignored");
                    vTaskDelay(pdMS_TO_TICKS(50)); 
                }
            } else if (event == EVENT_BUTTON_2) {
                int level = gpio_get_level(BTN_2_PIN);
                if (btn2_last_level < 0) btn2_last_level = level; // init
                ESP_LOGI(TAG, "Button2 interrupt, level=%d", level);
                
                if (btn2_last_level == 0 && level == 1) {                // rising edge
                    ESP_LOGI(TAG, "Button2 rising edge");
                    if (!LAMP_on) {
                        LAMP_turn_On();
                    }
                } else if (btn2_last_level == 1 && level == 0) {         // falling edge
                    ESP_LOGI(TAG, "Button2 falling edge");
                    if (LAMP_on) {
                        LAMP_turn_Off();
                    }
                }
                btn2_last_level = level;
                vTaskDelay(poll_delay);
            } else {
                ESP_LOGW(TAG, "Unknown button event: %d", event);
            }
        }
    }
} // button_task
/* * * * * * */
void task_blink_led(void *arg) {
    while (1) {
        gpio_set_level(LED_PIN, led_off);
        vTaskDelay(  freqLED / portTICK_PERIOD_MS);
        gpio_set_level(LED_PIN, led_on);
        vTaskDelay( 50 / portTICK_PERIOD_MS);
    }
} // task_blink_led

static void init_led(){
    gpio_reset_pin(LED_PIN);
    gpio_set_direction( LED_PIN, GPIO_MODE_OUTPUT);
	led_state = 0;
    xTaskCreate(&task_blink_led, "BlinkLed",  2048, NULL, 5, NULL);
} // init_led

//  / * / * / *
void lamp_timeout_task(void *arg) {
	const TickType_t xFrequency = ( SECONDS_PER_TICK * 1000 ) / portTICK_PERIOD_MS;
    while (1) {
      if ( total_seconds > 0 ) {
        if  ( total_seconds   > SECONDS_PER_TICK ) { 
          total_seconds -= SECONDS_PER_TICK;
        } else { 
          total_seconds  = 0;
          LAMP_turn_Off();
        }; // if
      } else if ( total_seconds < 0 ) {
        total_seconds = 0;
      } // if 
      vTaskDelay( xFrequency );
    } // while
} // lamp_timeout_task

void init_spiffs() {
    esp_vfs_spiffs_conf_t spiffs_conf = {
        .base_path = "/spiffs",
        .partition_label = NULL,
        .max_files = 5,
        .format_if_mount_failed = true
    };
    ESP_ERROR_CHECK(esp_vfs_spiffs_register(&spiffs_conf));
}; // init_spiffs

// --
/*
ESP32-C3 caution:

GPIO_NUM_11 — строб памяти (flash)
GPIO_NUM_12–GPIO_NUM_17 — internal SPI Flash/PSRAM

bool is_valid_gpio(gpio_num_t pin) {
    // Список допустимых GPIO для ESP32-C3 (без USB и стробов)
    const gpio_num_t valid_pins[] = {
        GPIO_NUM_0, GPIO_NUM_1, GPIO_NUM_2, GPIO_NUM_3, GPIO_NUM_4,
        GPIO_NUM_5, GPIO_NUM_6, GPIO_NUM_7, GPIO_NUM_8, GPIO_NUM_9,
        GPIO_NUM_10, GPIO_NUM_18, GPIO_NUM_19, GPIO_NUM_20, GPIO_NUM_21
    };
    const size_t count = sizeof(valid_pins) / sizeof(valid_pins[0]);

    for (size_t i = 0; i < count; i++) {
        if (valid_pins[i] == pin) {
            return true;
        }
    }
    return false;
}

if (pin_num < 0 || pin_num > 21) {
    ESP_LOGE(TAG, "GPIO %d out of range [0..21]", pin_num);
    return ESP_ERR_INVALID_ARG;
}

gpio_num_t gpio_pin = (gpio_num_t)pin_num;

if (!is_valid_gpio(gpio_pin)) {
    ESP_LOGE(TAG, "GPIO %d is not usable on ESP32-C3", pin_num);
    return ESP_ERR_INVALID_ARG;
}

*/

esp_err_t read_lamp_config_from_file(void) {
    ESP_LOGI(TAG, "Reading WiFi configuration from SPIFFS");

    FILE* file = fopen("/spiffs/lamp.cfg", "r");
    if (file == NULL) {
        ESP_LOGE(TAG, "Failed to open wifi_config.txt, using default values");
        return ESP_FAIL;
    }

    char temp_ssid[32] = {0};
    if (fgets(temp_ssid, sizeof(temp_ssid), file) == NULL) {
        ESP_LOGE(TAG, "Failed to read SSID");
        fclose(file);
        return ESP_FAIL;
    }
    temp_ssid[strcspn(temp_ssid, "\r")] = 0;
    strcpy(ap_cfg.ssid,   temp_ssid);
//    strncpy(ap_cfg.ssid, temp_ssid, sizeof(ap_cfg.ssid) - 1);
//    ap_cfg.ssid[sizeof(ap_cfg.ssid) - 1] = '\0';
    ESP_LOGI(TAG, "Read SSID:%s<<!!!", ap_cfg.ssid);

    char temp_password[64] = {0};
    if (fgets(temp_password, sizeof(temp_password), file) == NULL) {
        ESP_LOGE(TAG, "Failed to read password");
        fclose(file);
        return ESP_FAIL;
    }
    temp_password[strcspn(temp_password, "\r")] = 0;
    strcpy(ap_cfg.password, temp_password);
//    strncpy(ap_cfg.password, temp_password, sizeof(ap_cfg.password) - 1);
//    ap_cfg.password[sizeof(ap_cfg.password) - 1] = '\0';
    ESP_LOGI(TAG, "Read Password: %s<<!!!", ap_cfg.password);

    uint8_t wifi_channel  = 6;
    char temp_chan[8] = {0};
    if (!fgets(temp_chan, sizeof(temp_chan), file)) goto fail;
    temp_chan[strcspn(temp_chan, "\r\n")] = '\0';
	  ap_cfg.channel = atoi(temp_chan);
    ESP_LOGI(TAG, "Read wifi_channel=%d", wifi_channel);

	  ap_cfg.max_connections = 3;

    char led_str[8] = {0};
    if (!fgets(led_str, sizeof(led_str), file)) goto fail;
    led_str[strcspn(led_str, "\r\n")] = '\0';
    int led_num = atoi(led_str);
//    if (led_num < 0 || led_num > 21) goto fail;
    LED_STRIP_GPIO = (gpio_num_t)led_num;
//    if (!is_valid_gpio(LED_STRIP_GPIO)) goto fail;

    char btn_str[8] = {0};
    if (!fgets(btn_str, sizeof(btn_str), file)) goto fail;
    btn_str[strcspn(btn_str, "\r\n")] = '\0';
    int btn_num = atoi(btn_str);
//    if (btn_num < 0 || btn_num > 21) goto fail;
    BTN_1_PIN = (gpio_num_t)btn_num;
//    if (!is_valid_gpio(BTN_1_PIN)) goto fail;

    char btn2_str[8] = {0};
    if (!fgets(btn2_str, sizeof(btn2_str), file)) goto fail;
    btn2_str[strcspn(btn2_str, "\r\n")] = '\0';
    int btn2_num = atoi(btn2_str);
    BTN_2_PIN = (gpio_num_t)btn2_num;

    ESP_LOGI(TAG, ">>>>>>>>Read LED_STRIP_GPIO=%d, BTN_1_PIN=%d, BTN_2_PIN=%d", LED_STRIP_GPIO, BTN_1_PIN, BTN_2_PIN);

    fclose(file);
    return ESP_OK;
fail:
    fclose(file);
    ESP_LOGE(TAG, "Config file format error");
    return ESP_FAIL;
} // read_lamp_config_from_file
// ***************
void app_main()
{
    esp_err_t ret = nvs_flash_init();
    if (ret == ESP_ERR_NVS_NO_FREE_PAGES || ret == ESP_ERR_NVS_NEW_VERSION_FOUND) {
        ESP_ERROR_CHECK(nvs_flash_erase());
        ret = nvs_flash_init();
    }
    ESP_ERROR_CHECK(ret);
    init_spiffs();

    init_led();
    if ( xTaskCreate(&lamp_timeout_task,   "countdown", 2048, NULL, 5, NULL) != pdPASS) {
      ESP_LOGE(TAG, "Failed to create lamp_timeout_task!");
    }
    if (read_lamp_config_from_file() != ESP_OK) {
        ESP_LOGE(TAG, "Failed to read Lamp configuration!");
        return;
    }

    gpio_config_t io_conf1 = {
        .mode = GPIO_MODE_INPUT,
        .pin_bit_mask = (1ULL << BTN_1_PIN),
        .intr_type = GPIO_INTR_POSEDGE,
        .pull_down_en = GPIO_PULLDOWN_DISABLE,
        .pull_up_en = GPIO_PULLUP_ENABLE
    };
    ESP_ERROR_CHECK(gpio_config(&io_conf1));

    gpio_config_t io_conf2 = {
        .mode = GPIO_MODE_INPUT,
        .pin_bit_mask = (1ULL << BTN_2_PIN),
        .intr_type = GPIO_INTR_ANYEDGE,
        .pull_down_en = GPIO_PULLDOWN_DISABLE,
        .pull_up_en = GPIO_PULLUP_DISABLE
    };
    ESP_ERROR_CHECK(gpio_config(&io_conf2));

    vTaskDelay(pdMS_TO_TICKS(10));
    int button_state = gpio_get_level(BTN_1_PIN);
    if (button_state == 1) {
      ESP_LOGI(TAG, "+++ ++ ++ ++  btn ... ... ... ...\n");
    } else {
      ESP_LOGI(TAG, "--- -- -- --  btn ... ... ... ...\n");
	  };

	  ESP_LOGI(TAG, "              btn ... handler ... ...\n");

    gpio_install_isr_service(0);
    gpio_isr_handler_add(BTN_1_PIN, gpio_isr_handler, (void*)BTN_1_PIN);
    gpio_isr_handler_add(BTN_2_PIN, gpio_isr_handler, (void*)BTN_2_PIN);

    button_queue = xQueueCreate(10, sizeof(button_event_t));
    if (button_queue == NULL) {
        ESP_LOGE(TAG, "Ошибка создания очереди!");
        return;
    }
    xTaskCreate(button_task, "button_task", 2048, NULL, 10, NULL);

    ESP_LOGI(TAG, "ESP_WIFI_MODE_AP");

    ESP_LOGI(TAG, "ESP32 ESP-IDF WebSocket Web Server is running ... ...\n");
    webserver_init_buffers();
    ESP_LOGI(TAG, "+++ +++ +++ +++  setup_websocket_server ... ... ... ...\n");
    webserver_start(&ap_cfg);
    LAMP_init();
} // main
// components/webserver/include/webserver.h
// esp-idf v 5.5.1
//
#ifndef WEBSERVER_H
#define WEBSERVER_H

#include "esp_err.h"
#include "esp_http_server.h"

typedef struct {
    char ssid[33];
    char password[65];
    uint8_t    channel;
    uint8_t    max_connections;
} webserver_ap_config_t;

/**
 * @brief Инициализирует буферы веб-страниц из SPIFFS
 * @return ESP_OK при успехе
 */

esp_err_t webserver_init_buffers(void);

/**
 * @brief Инициализирует Wi-Fi в режиме SoftAP и запускает HTTP/WebSocket сервер.
 *
 * Должен вызываться ПОСЛЕ nvs_flash_init() и esp_netif_init().
 *
 * @param config  Конфигурация точки доступа (не NULL)
 * @return        ESP_OK при успехе
 */
esp_err_t webserver_start(const webserver_ap_config_t *AP_cfg);

#endif // WEBSERVER_H
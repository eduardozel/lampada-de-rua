// components/webserver/src/webserver.c
// esp-idf v 5.5.1
//
#include "webserver.h"
#include <stdio.h>
#include <string.h>
#include <sys/stat.h>

#include <esp_http_server.h>
#include "esp_log.h"


#include <esp_http_server.h>
#include "esp_mac.h"
#include "esp_wifi.h"

#include "esp_event.h"


#include "cJSON.h"


#include "lamp.h"

static const char *TAG = "webserver";

#define INDEX_HTML_PATH "/spiffs/start.html"
#define STYLE_CSS_PATH  "/spiffs/styles.css"
#define SCRIPT_JS_PATH  "/spiffs/script.js"


static char index_html[4096];
static char style_css[4096 + 512];
static char script_js[4096 + 4096 + 1024];

static esp_netif_t *ap_netif = NULL;

httpd_handle_t server = NULL;

struct async_resp_arg {
    httpd_handle_t hd;
    int fd;
};


static esp_err_t load_file_to_buffer( const char *path
                                    , char *buffer
									, size_t buf_size
) {
    struct stat st;
    if (stat(path, &st) != 0) {
        ESP_LOGE(TAG, "File not found: %s", path);
        return ESP_ERR_NOT_FOUND;
    }
    if (st.st_size >= buf_size) {
        ESP_LOGE(TAG, "File too large: %s (%ld bytes)", path, st.st_size);
        return ESP_ERR_NO_MEM;
    }

    FILE *fp = fopen(path, "r");
    if (!fp) {
        ESP_LOGE(TAG, "Failed to open %s", path);
        return ESP_FAIL;
    }

    size_t read = fread(buffer, 1, st.st_size, fp);
    fclose(fp);

    if (read != st.st_size) {
        ESP_LOGE(TAG, "Failed to read %s", path);
        return ESP_FAIL;
    }

    buffer[read] = '\0';
    ESP_LOGI(TAG, "Loaded %s (%d bytes)", path, (int)read);
    return ESP_OK;
} // load_file_to_buffer


static void ws_async_send(void *arg)
{
    httpd_ws_frame_t ws_pkt;
    struct async_resp_arg *resp_arg = arg;
    httpd_handle_t hd = resp_arg->hd;
//    int fd = resp_arg->fd;

    char buffer[128];

	int len = lamp_settings_json(buffer, sizeof(buffer));
    if (len < 0 || len >= sizeof(buffer)) {
        ESP_LOGE(TAG, "Failed to format JSON or buffer too small");
        return;
    }
    ESP_LOGI(TAG, "msg: %s", buffer);
    
    memset(&ws_pkt, 0, sizeof(httpd_ws_frame_t));
    ws_pkt.payload = (uint8_t *)buffer;
    ws_pkt.len     = len;
    ws_pkt.type    = HTTPD_WS_TYPE_TEXT;

    static size_t max_clients = CONFIG_LWIP_MAX_LISTENING_TCP;
    size_t fds = max_clients;
    int client_fds[max_clients];

    esp_err_t ret = httpd_get_client_list(server, &fds, client_fds);

    if (ret != ESP_OK) {
        return;
    }

    for (int i = 0; i < fds; i++) {
        int client_info = httpd_ws_get_fd_info(server, client_fds[i]);
        if (client_info == HTTPD_WS_CLIENT_WEBSOCKET) {
            httpd_ws_send_frame_async(hd, client_fds[i], &ws_pkt);
        }
    }
    free(resp_arg);
}

static esp_err_t trigger_async_send(httpd_handle_t handle, httpd_req_t *req)
{
    struct async_resp_arg *resp_arg = malloc(sizeof(struct async_resp_arg));
    resp_arg->hd = req->handle;
    resp_arg->fd = httpd_req_to_sockfd(req);
    return httpd_queue_work(handle, ws_async_send, resp_arg);
}


/*
static void webserver_init_buffers(void)
{
	ESP_LOGI(TAG, "=== === === ===  init_web_page_buffer ... ... ... ...\n");

    memset((void *)index_html, 0, sizeof(index_html));
    struct stat st;
    if (stat(INDEX_HTML_PATH, &st))
    {
        ESP_LOGE(TAG, "not found : start.html");
        return;
    }

    FILE *fp = fopen(INDEX_HTML_PATH, "r");
    if (fread(index_html, st.st_size, 1, fp) == 0)
    {
        ESP_LOGE(TAG, "fread failed");
		return;
	} else { 
		if (st.st_size >= sizeof(index_html)) {
			ESP_LOGE(TAG, "index.html too large (%ld bytes), buffer is %d", st.st_size, sizeof(index_html));
			return;
        } else {
           ESP_LOGI(TAG, "Loaded start.html, size: %ld", st.st_size);
		};
    }
    fclose(fp);
// CSS ----
    memset((void *)style_css, 0, sizeof(style_css));
    if (stat(STYLE_CSS_PATH, &st))
    {
        ESP_LOGE(TAG, "Failed to open %s", STYLE_CSS_PATH);
        return;
    }

    if (st.st_size >= sizeof(style_css)) {
        ESP_LOGE(TAG, "styles.css too large (%ld bytes), buffer is %d", st.st_size, sizeof(style_css));
        return;
    }

    fp = fopen(STYLE_CSS_PATH, "r");
    size_t css_size = fread(style_css, 1, st.st_size, fp);
//    if (fread(style_css, st.st_size, 1, fp) == 0) {
	if (css_size == 0) {
        ESP_LOGE(TAG, "fread failed for styles.css");
    } else {
        style_css[css_size] = '\0';  // Нулевой терминатор (не обязательно для CSS, но полезно для strlen)
        ESP_LOGI(TAG, "Loaded styles.css, size: %d", css_size);
    } // if
    fclose(fp);
// JS
    memset((void *)script_js, 0, sizeof(script_js));
    if (stat(SCRIPT_JS_PATH, &st)) {
        ESP_LOGE(TAG, "not found: %s", SCRIPT_JS_PATH);
        return;
    }
    if (st.st_size >= sizeof(script_js)) {
        ESP_LOGE(TAG, "script.js too large (%ld bytes)", st.st_size);
        return;
    }
    fp = fopen(SCRIPT_JS_PATH, "r");
    if (fp == NULL) {
        ESP_LOGE(TAG, "Failed to open %s", SCRIPT_JS_PATH);
        return;
    }
    size_t js_size = fread(script_js, 1, st.st_size, fp);
    if (js_size == 0) {
        ESP_LOGE(TAG, "fread failed for script.js");
    } else {
        script_js[js_size] = '\0';
        ESP_LOGI(TAG, "Loaded script.js,  size: %d", js_size);
    }
    fclose(fp);

} // init_web_page_buffer
*/


esp_err_t webserver_init_buffers(void) {
    ESP_LOGI(TAG, "Loading web assets from SPIFFS...");

    memset(index_html, 0, sizeof(index_html));
    memset(style_css,  0, sizeof(style_css));
    memset(script_js,  0, sizeof(script_js));

    esp_err_t err;
    if ((err = load_file_to_buffer("/spiffs/start.html", index_html, sizeof(index_html))) != ESP_OK) return err;
    if ((err = load_file_to_buffer("/spiffs/styles.css", style_css,  sizeof(style_css)))  != ESP_OK) return err;
    if ((err = load_file_to_buffer("/spiffs/script.js",  script_js,  sizeof(script_js)))  != ESP_OK) return err;

    return ESP_OK;
} // webserver_init_buffers


esp_err_t style_handler(httpd_req_t *req) {
    ESP_LOGI(TAG, "---------style_handler ---------styles.css");
    if (style_css[0] == '\0') {  // Если буфер пустой (не загружен)
        ESP_LOGE(TAG, "styles.css not loaded in memory");
        httpd_resp_send_404(req);
        return ESP_FAIL;
    }

    size_t css_len = strlen(style_css);  // Длина из буфера (благодаря \0)
    ESP_LOGI(TAG, "Serving styles.css from memory, size: %d", css_len);

     httpd_resp_set_type(req, "text/css");    // Устанавливаем тип контента (важно для браузера!)

    // Опционально: заголовки для кэша (чтобы браузер не запрашивал каждый раз)
    httpd_resp_set_hdr(req, "Cache-Control", "max-age=3600");  // Кэш на 1 час; no-cache для тестов.
//    httpd_resp_set_hdr(req, "Cache-Control", "no-cache");  // no-cache для тестов.
//    httpd_resp_set_hdr(req, "Cache-Control", "no-cache, no-store, must-revalidate")

    esp_err_t ret = httpd_resp_send(req, style_css, css_len);     // Отправляем данные одним куском (поскольку файл маленький)
    if (ret != ESP_OK) {
        ESP_LOGE(TAG, "Failed to send styles.css: %s", esp_err_to_name(ret));
        return ret;
    }
    return ESP_OK;
} // style_handler


esp_err_t js_handler(httpd_req_t *req) {
    if (script_js[0] == '\0') {
        ESP_LOGE(TAG, "script.js not loaded");
        httpd_resp_send_404(req);
        return ESP_FAIL;
    }
    size_t js_len = strlen(script_js);
    ESP_LOGI(TAG, "Serving script.js from memory, size: %d", js_len);
    httpd_resp_set_type(req, "application/javascript");
    //httpd_resp_set_hdr(req, "Cache-Control", "max-age=3600"); // Кэш на 1 час;
    httpd_resp_set_hdr(req, "Cache-Control", "no-cache"); // no-cache для тестов.
    return httpd_resp_send(req, script_js, js_len);
} // js_handler

static esp_err_t get_req_handler(httpd_req_t *req) {
    // Обработка GET-запроса для корневого пути
    httpd_resp_send(req, index_html, HTTPD_RESP_USE_STRLEN);
    return ESP_OK;
}
/* * * * * */
static esp_err_t upload_handler(httpd_req_t *req) {
    char buf[1024];
    esp_err_t ret;
    size_t remaining = req->content_len;

    FILE *fd = fopen("/spiffs/led_sequence.cfg", "w");
    if (fd == NULL) {
        ESP_LOGE(TAG, "Failed to open file for writing");
        httpd_resp_send_500(req);
        return ESP_FAIL;
    }

    while (remaining > 0) {
        size_t buf_len = sizeof(buf) < remaining ? sizeof(buf) : remaining;
        ret = httpd_req_recv(req, buf, buf_len);
        if (ret <= 0) {
            if (ret == HTTPD_SOCK_ERR_TIMEOUT) {
                continue;
            }
            fclose(fd);
            ESP_LOGE(TAG, "File receive failed");
            httpd_resp_send_500(req);
            return ESP_FAIL;
        }
        if (fwrite(buf, 1, ret, fd) != ret) {
            fclose(fd);
            ESP_LOGE(TAG, "File write failed");
            httpd_resp_send_500(req);
            return ESP_FAIL;
        }
        remaining -= ret;
    }

    fclose(fd);
    ESP_LOGI(TAG, "File uploaded and saved as /spiffs/led_sequence.cfg");
    const char *resp = "File uploaded successfully";
    httpd_resp_send(req, resp, strlen(resp));
    return ESP_OK;
}

void monitor_wifi_status() {
    wifi_ap_record_t ap_info;
    esp_err_t ret = esp_wifi_sta_get_ap_info(&ap_info);
    if (ret == ESP_OK) {
        ESP_LOGI(TAG, "RSSI: %d dBm", ap_info.rssi);
    }
} // monitor_wifi_status


static esp_err_t handle_ws_req(httpd_req_t *req)
{
    ESP_LOGI(TAG, "===================== handle_ws_req");
    if (req->method == HTTP_GET)
    {
        ESP_LOGI(TAG, "Handshake done, the new connection was opened");
        return ESP_OK;
    }

    httpd_ws_frame_t ws_pkt;
    uint8_t *buf = NULL;
    memset(&ws_pkt, 0, sizeof(httpd_ws_frame_t));
    ws_pkt.type = HTTPD_WS_TYPE_TEXT;
    esp_err_t ret = httpd_ws_recv_frame(req, &ws_pkt, 0);
    if (ret != ESP_OK)
    {
        ESP_LOGE(TAG, "httpd_ws_recv_frame failed to get frame len with %d", ret);
        return ret;
    }

    ESP_LOGI(TAG, "Frame length: %d", ws_pkt.len);
	if (ws_pkt.len > 512) {
        ESP_LOGE(TAG, "Frame too long: %d", ws_pkt.len);
        return ESP_FAIL;
    }
	
    if (ws_pkt.len)
    {
        buf = calloc(1, ws_pkt.len + 1);
        if (buf == NULL)
        {
            ESP_LOGE(TAG, "Failed to calloc memory for buf");
            return ESP_ERR_NO_MEM;
        }
        ws_pkt.payload = buf;
        ret = httpd_ws_recv_frame(req, &ws_pkt, ws_pkt.len);
        if (ret != ESP_OK)
        {
            ESP_LOGE(TAG, "httpd_ws_recv_frame failed with %d", ret);
            free(buf);
            return ret;
        }
        ESP_LOGI(TAG, "Got packet with message: %s", ws_pkt.payload);
    }

    ESP_LOGI(TAG, "frame len is %d", ws_pkt.len);

    if (ws_pkt.type == HTTPD_WS_TYPE_TEXT) {
		if ( strcmp((char *)ws_pkt.payload, "toggle") == 0) {
          free(buf);
          return trigger_async_send(req->handle, req);
		} else {
			cJSON *json = cJSON_Parse((char *)ws_pkt.payload);
			free(buf);
			if (json) {
				bool cmdStart = false;
				bool cmdStop  = false;
				bool cmdOnOff = false; 
				const char *action = cJSON_GetObjectItem(json, "act")->valuestring;
				if (strcmp(action, "start") == 0) {
					cmdStart = true;
				} else if (strcmp(action, "stop") == 0) {
					cmdStop = true;
				} else if (strcmp(action, "togglelight") == 0) {
					cmdOnOff = true;
				} else if (strcmp(action, "getState") == 0) {
					return trigger_async_send(req->handle, req);
				}

				if ( cmdStart  ) {
					monitor_wifi_status();
					lamp_settings_from_json(json);
				}; // cmdStart

				if ( cmdStop ) {
					LAMP_turn_Off();
				}
				if ( cmdOnOff ) {
                  if (!LAMP_on) {
                    LAMP_turn_On();
                  } else {
				    LAMP_turn_Off();
                  } // if LAMP_on				
				}
			} // if (json)
			cJSON_Delete(json);
		}
	} else if (ws_pkt.type == HTTPD_WS_TYPE_CLOSE) {
      ESP_LOGI(TAG, "WebSocket connection closed by client");
	  if ( buf != NULL ) {
        free(buf);
	  }
      return ESP_OK;
    }
    return ESP_OK;
} // handle_ws_req

esp_err_t not_found_handler(httpd_req_t *req) {
    ESP_LOGE(TAG, "404: URI not found: %s", req->uri);
    httpd_resp_send_404(req);
    return ESP_OK;
} // not_found_handler

void set_wifi_tx_power() {
    int8_t max_tx_power = 1; // 80 -> 20 dBm  ; 1 -> 0.25 dBm
    esp_wifi_set_max_tx_power(max_tx_power);
} // set_wifi_tx_power

static void wifi_event_handler(void* arg, esp_event_base_t event_base,
                                    int32_t event_id, void* event_data)
{
    if (event_id == WIFI_EVENT_AP_STACONNECTED) {
        wifi_event_ap_staconnected_t* event = (wifi_event_ap_staconnected_t*) event_data;
//		freqLED = 3000;
        ESP_LOGI(TAG, "station "MACSTR" join, AID=%d",
                 MAC2STR(event->mac), event->aid);
    } else if (event_id == WIFI_EVENT_AP_STADISCONNECTED) {
        wifi_event_ap_stadisconnected_t* event = (wifi_event_ap_stadisconnected_t*) event_data;
//		freqLED = 10000;
        ESP_LOGI(TAG, "station "MACSTR" leave, AID=%d",
                 MAC2STR(event->mac), event->aid);
    }
} // wifi_event_handler

//- - - - - -
esp_err_t webserver_start(const webserver_ap_config_t *AP_cfg) {
  if (!AP_cfg) {
    ESP_LOGE(TAG, "Invalid config");
    return ESP_ERR_INVALID_ARG;
  }
  ESP_LOGI(TAG, "---------------SSID>%s<password>%s", AP_cfg->ssid, AP_cfg->password);
  set_wifi_tx_power();

  ESP_ERROR_CHECK(esp_netif_init());
  ESP_ERROR_CHECK(esp_event_loop_create_default());

  ap_netif = esp_netif_create_default_wifi_ap();
  assert(ap_netif);	
	
  wifi_init_config_t wifi_init_cfg = WIFI_INIT_CONFIG_DEFAULT();
  ESP_ERROR_CHECK(esp_wifi_init(&wifi_init_cfg));
  ESP_ERROR_CHECK(esp_wifi_set_storage(WIFI_STORAGE_RAM));
  ESP_ERROR_CHECK(esp_event_handler_instance_register( WIFI_EVENT,
                                                       ESP_EVENT_ANY_ID,
                                                       &wifi_event_handler,
 													   NULL,
 													   NULL));

   wifi_config_t wifi_cfg = {0};
   size_t ssid_len = strlen(AP_cfg->ssid);
   if (ssid_len == 0 || ssid_len > 32) {
     ESP_LOGE(TAG, "Invalid SSID length");
     return ESP_ERR_INVALID_ARG;
   }
   memcpy(wifi_cfg.ap.ssid, AP_cfg->ssid, ssid_len);
   wifi_cfg.ap.ssid_len = ssid_len;

   if ( strlen(AP_cfg->password) > 0) {
        if (strlen(AP_cfg->password) < 8) {
            ESP_LOGE(TAG, "Password too short (<8 chars)>%s<>%s", AP_cfg->password, AP_cfg->ssid);
            return ESP_ERR_INVALID_ARG;
        }
        strncpy((char *)wifi_cfg.ap.password, AP_cfg->password, sizeof(wifi_cfg.ap.password) - 1);
        wifi_cfg.ap.authmode = WIFI_AUTH_WPA2_PSK;
   } else {
        wifi_cfg.ap.authmode = WIFI_AUTH_OPEN;
   }
   ESP_LOGI(TAG, "SSID>%s<password>%s", AP_cfg->ssid, AP_cfg->password);

   wifi_cfg.ap.channel = AP_cfg->channel;
   wifi_cfg.ap.max_connection = AP_cfg->max_connections;
   wifi_cfg.ap.pmf_cfg.required = false;

   ESP_ERROR_CHECK(esp_wifi_set_mode(WIFI_MODE_AP));
   ESP_ERROR_CHECK(esp_wifi_set_config(WIFI_IF_AP, &wifi_cfg));
   ESP_ERROR_CHECK(esp_wifi_start());

   ESP_LOGI(TAG, "SoftAP started: SSID='%s', channel=%d", AP_cfg->ssid, AP_cfg->channel);
// - * - * - * - * 

//    static httpd_handle_t server = NULL;
    if (server) return ESP_ERR_INVALID_STATE;

    httpd_config_t httpd_cfg = HTTPD_DEFAULT_CONFIG();
    if (httpd_start(&server, &httpd_cfg) != ESP_OK) {
        ESP_LOGE(TAG, "Failed to start HTTPD");
        return ESP_FAIL;
    }

    httpd_uri_t uris[] = {
        {.uri = "/",          .method = HTTP_GET, .handler = get_req_handler,   .user_ctx = NULL},
        {.uri = "/styles.css",.method = HTTP_GET, .handler = style_handler,     .user_ctx = NULL},
        {.uri = "/script.js", .method = HTTP_GET, .handler = js_handler,        .user_ctx = NULL},
        {.uri = "/upload",    .method = HTTP_POST,.handler = upload_handler,    .user_ctx = NULL},
        {.uri = "/ws",        .method = HTTP_GET, .handler = handle_ws_req,     .user_ctx = NULL, .is_websocket = true},
        {.uri = "/*",         .method = HTTP_GET, .handler = not_found_handler, .user_ctx = NULL},
    };

    for (size_t i = 0; i < sizeof(uris)/sizeof(uris[0]); i++) {
        httpd_register_uri_handler(server, &uris[i]);
    }

    ESP_LOGI(TAG, "Web server started on http://<ip>/");
    return ESP_OK;
} // webserver_start


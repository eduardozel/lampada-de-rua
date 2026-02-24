#include "WS2812.h"

#include <stdint.h>
#include <math.h>

#include <stdlib.h>
#include "esp_log.h"
#include <string.h>

#include "freertos/FreeRTOS.h"

#include "driver/rmt_tx.h"
#include "led_strip_encoder.h"

#define RMT_LED_STRIP_RESOLUTION_HZ 10000000 // 10MHz resolution, 1 tick = 0.1us (led strip needs a high resolution)

#define EXAMPLE_CHASE_SPEED_MS      10

// 16 ступеней (массив, уровни 1-16)
const rgb_t warm_white_steps[16] = {
    { 16,  12,  6}, { 32,  24, 13}, { 48,  36, 19}, { 64,  48,  25},
    { 80,  59, 31}, { 96,  71, 38}, {112,  83, 44}, {128,  95,  50},
    {144, 107, 56}, {159, 119, 63}, {175, 131, 69}, {191, 143,  75},
    {207, 154, 81}, {223, 166, 88}, {239, 178, 94}, {255, 190, 100}
};

static const char *TAG = "ws2812";

gpio_num_t  LED_STRIP_GPIO;

static int brightness = 4;

static uint8_t led_strip_pixels[MAX_LED_NUMBERS * 3];

led_state_t *led_states = NULL;

    rmt_channel_handle_t       led_chan;
    rmt_tx_channel_config_t    tx_chan_config;
	led_strip_encoder_config_t encoder_config;
	rmt_encoder_handle_t       led_encoder;
    rmt_transmit_config_t      tx_config;
// - - - - - - - - - - -
/**
 * @brief Simple helper function, converting HSV color space to RGB color space
 *
 * Wiki: https://en.wikipedia.org/wiki/HSL_and_HSV
 * https://github.com/espressif/esp-idf/tree/master/examples/peripherals/rmt/led_strip_simple_encoder
 */
 
 /*
void led_strip_hsv2rgb(uint32_t h, uint32_t s, uint32_t v, uint32_t *r, uint32_t *g, uint32_t *b)
{
    h %= 360; // h -> [0,360]
    uint32_t rgb_max = v * 2.55f;
    uint32_t rgb_min = rgb_max * (100 - s) / 100.0f;

    uint32_t i = h / 60;
    uint32_t diff = h % 60;

    // RGB adjustment amount by hue
    uint32_t rgb_adj = (rgb_max - rgb_min) * diff / 60;

    switch (i) {
    case 0:
        *r = rgb_max;
        *g = rgb_min + rgb_adj;
        *b = rgb_min;
        break;
    case 1:
        *r = rgb_max - rgb_adj;
        *g = rgb_max;
        *b = rgb_min;
        break;
    case 2:
        *r = rgb_min;
        *g = rgb_max;
        *b = rgb_min + rgb_adj;
        break;
    case 3:
        *r = rgb_min;
        *g = rgb_max - rgb_adj;
        *b = rgb_max;
        break;
    case 4:
        *r = rgb_min + rgb_adj;
        *g = rgb_min;
        *b = rgb_max;
        break;
    default:
        *r = rgb_max;
        *g = rgb_min;
        *b = rgb_max - rgb_adj;
        break;
    }
}
*/


rgb_t hsv2rgb(float h, float s, float v) {
    h = fmodf(h, 360.0f); // Нормализация hue в диапазон [0, 360)
    float c = v * s;
    float x = c * (1.0f - fabsf(fmodf(h / 60.0f, 2.0f) - 1.0f));
    float m = v - c;
    
    float rp, gp, bp;
    if (h >= 0 && h < 60) {
        rp = c; gp = x; bp = 0;
    } else if (h >= 60 && h < 120) {
        rp = x; gp = c; bp = 0;
    } else if (h >= 120 && h < 180) {
        rp = 0; gp = c; bp = x;
    } else if (h >= 180 && h < 240) {
        rp = 0; gp = x; bp = c;
    } else if (h >= 240 && h < 300) {
        rp = x; gp = 0; bp = c;
    } else {
        rp = c; gp = 0; bp = x;
    }
    
    rgb_t color;
    color.red   = (uint8_t)((rp + m) * 255);
    color.green = (uint8_t)((gp + m) * 255);
    color.blue  = (uint8_t)((bp + m) * 255);
    return color;
} // hsv2rgb

void initWS2812()
{
    ESP_LOGI(TAG, "Create RMT TX channel");
    led_chan = NULL;
    tx_chan_config = (rmt_tx_channel_config_t) {
        .clk_src = RMT_CLK_SRC_DEFAULT, // select source clock
//        .gpio_num = RMT_LED_STRIP_GPIO_NUM,
        .gpio_num = LED_STRIP_GPIO,
        .mem_block_symbols = 64, // increase the block size can make the LED less flickering
        .resolution_hz = RMT_LED_STRIP_RESOLUTION_HZ,
        .trans_queue_depth = 4, // set the number of transactions that can be pending in the background
    };
    ESP_ERROR_CHECK(rmt_new_tx_channel(&tx_chan_config, &led_chan));

    ESP_LOGI(TAG, "Install led strip encoder");
    led_encoder = NULL;
    encoder_config = (led_strip_encoder_config_t) {
        .resolution = RMT_LED_STRIP_RESOLUTION_HZ,
    };
    ESP_ERROR_CHECK(rmt_new_led_strip_encoder(&encoder_config, &led_encoder));

    ESP_LOGI(TAG, "Enable RMT TX channel");
    ESP_ERROR_CHECK(rmt_enable(led_chan));
	
	tx_config = (rmt_transmit_config_t) {
        .loop_count = 0, // no transfer loop
    };
} // initWS2812

void offAllLED( )
{
	memset(led_strip_pixels, 0, sizeof(led_strip_pixels));
	ESP_ERROR_CHECK(rmt_transmit(led_chan, led_encoder, led_strip_pixels, sizeof(led_strip_pixels), &tx_config));
	ESP_ERROR_CHECK(rmt_tx_wait_all_done(led_chan, portMAX_DELAY));
//	vTaskDelay(pdMS_TO_TICKS(EXAMPLE_CHASE_SPEED_MS));
} // offAllLED

void setAllLED_rgb( uint8_t red, uint8_t green, uint8_t blue )
{
	for (int j = 0; j < MAX_LED_NUMBERS; j ++) {
//	            led_strip_hsv2rgb(hue, 100, 100, &red, &green, &blue);
		led_strip_pixels[j * 3 + 0] = green;
		led_strip_pixels[j * 3 + 1] = red;
		led_strip_pixels[j * 3 + 2] = blue;
	}
	ESP_ERROR_CHECK(rmt_transmit(led_chan, led_encoder, led_strip_pixels, sizeof(led_strip_pixels), &tx_config));
    ESP_ERROR_CHECK(rmt_tx_wait_all_done(led_chan, portMAX_DELAY));
} // setAllLED_rgb

void setAllLED( rgb_t color ){
  setAllLED_rgb( color.red, color.green, color.blue );
};// setAllLED

void setLEDsArray(rgb_t *led_array, size_t count)
{
    if (count > MAX_LED_NUMBERS) {
        count = MAX_LED_NUMBERS;
    }

    for (size_t i = 0; i < count; i++) {
        led_strip_pixels[i * 3 + 0] = led_array[i].green;
        led_strip_pixels[i * 3 + 1] = led_array[i].red;
        led_strip_pixels[i * 3 + 2] = led_array[i].blue;
    }

    for (size_t i = count; i < MAX_LED_NUMBERS; i++) {
        led_strip_pixels[i * 3 + 0] = 0;
        led_strip_pixels[i * 3 + 1] = 0;
        led_strip_pixels[i * 3 + 2] = 0;
    }

    ESP_ERROR_CHECK(rmt_transmit(led_chan, led_encoder, led_strip_pixels, sizeof(led_strip_pixels), &tx_config));
    ESP_ERROR_CHECK(rmt_tx_wait_all_done(led_chan, portMAX_DELAY));
} // setLEDsArray

void setProfileN( int prfl )
{//   ESP_LOGI(TAG, "setProfile>>%d",prfl);
    led_state_t *state = &led_states[prfl];
    setLEDsArray(state->leds, MAX_LED_NUMBERS);
} // setProfile

void fade_in_warm_white( int max )
{
    brightness = max;
	for (int step = 0; step < max; step++) {
//      ws2812_send(&warm_white_steps[step], LED_COUNT);
      setAllLED_rgb( warm_white_steps[step].red, warm_white_steps[step].green, warm_white_steps[step].blue);
	  vTaskDelay(500 / portTICK_PERIOD_MS);
    }
} // fade_in_warm_white



//set_warm_white(); 
// Теплый белый: GRB = 210, 255, 150
/*
Дополнительно: Вы можете настроить яркость, уменьшая значения, но сохраняя пропорции.
 Пример с яркостью 50%:
   G = 105, R = 127, B = 75
 Но будьте осторожны: из-за нелинейности восприятия глаза, простое уменьшение в 2 раза может дать не совсем теплый белый.
 
 Теплый белый: Используйте значения:

    0xFF9632 (255, 150, 50) - стандартный теплый

    0xFF8B1A (255,139,026) - более теплый оттенок
	
	
 */
 
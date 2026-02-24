#ifndef WS2812_H_
#define WS2812_H_

#include <stdlib.h>
#include <string.h>
#include <inttypes.h>
#include "driver/gpio.h"

#define MAX_LED_NUMBERS 40


typedef struct {
    uint8_t red;
    uint8_t green;
    uint8_t blue;
} rgb_t;

typedef struct {
    rgb_t leds[MAX_LED_NUMBERS];
    int duration_sec;
} led_state_t;

extern led_state_t *led_states;
extern gpio_num_t LED_STRIP_GPIO;

void initWS2812( void );
void setAllLED_rgb( uint8_t red, uint8_t green, uint8_t blue ); // uint8_t uint32_t
void setAllLED( rgb_t color );
void setProfileN( int prfl );
void offAllLED( void );
void fade_in_warm_white( int max );
rgb_t hsv2rgb(float h, float s, float v);

#endif
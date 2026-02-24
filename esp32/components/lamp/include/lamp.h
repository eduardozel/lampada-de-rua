#ifndef lamp_H_
#define lamp_H_

#include <stdlib.h>
#include <string.h>
#include <math.h>
#include <stdbool.h>
#include "cJSON.h"

#include "freertos/FreeRTOS.h"
#include "freertos/task.h"

#include "WS2812.h"

extern volatile int total_seconds;

extern bool  LAMP_on;

extern bool rainbow_active;
extern void rainbow_task(void *arg);
extern TaskHandle_t rainbow_task_handle;

void  lamp_settings_from_json(cJSON *json);
int   lamp_settings_json(char *buffer, size_t size) ;

void LAMP_init(void);
void LAMP_turn_On(void);
void LAMP_turn_Off(void);


#endif
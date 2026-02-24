//
// lamp.c
//
#include "freertos/FreeRTOS.h"
#include "freertos/task.h"
#include "esp_system.h"
#include "esp_log.h"

#include <stdlib.h>
#include <string.h>
#include <math.h>


#include "cJSON.h"

#include "lamp.h"


#define CONFIG_FILE     "/spiffs/lighting.cfg"

static const char *TAG = "LAMP";

static int led_states_count = 0;
static const char* sequence_filename = "/spiffs/led_sequence.cfg";


/*
static const char *lamp_state_to_str(LAMP_state_t s)
{
    switch (s) {
    case white:   return "white";
    case rainbow: return "rainbow";
    case custom:  return "custom";
    default:      return "unknown";
    }
}
*/
/*
static int lamp_state_from_str(const char *s, LAMP_state_t *out)
{
    if (!s || !out) return -1;
    if (strcasecmp(s, "white") == 0) {
        *out = white;
    } else if (strcasecmp(s, "rainbow") == 0) {
        *out = rainbow;
    } else if (strcasecmp(s, "color") == 0) {
        *out = custom;
    } else { // попробовать прочитать как число:
        char *end; 
        long v = strtol(s, &end, 10);
        if (end != s) {
            if (v == white || v == rainbow || v == custom) {
                *out = (LAMP_state_t)v;
            } else {
                return -1;
            }
        } else {
            return -1;
        }
    }
    return 0;
}
*/

typedef enum {
    white    = 0,
    rainbow  = 1,
    custom   = 2,
	profile  = 3,
	profileN = 4
} LAMP_state_t;


bool LAMP_on   = false;
int lamp_start_on = 1;

LAMP_state_t lamp_state = white;

bool         rainbow_active = false;
TaskHandle_t rainbow_task_handle = NULL;

bool         profile_active = false;
TaskHandle_t profile_task_handle = NULL;


int   current_duration = 5*60;
volatile int total_seconds = 0;
int   brightness = 4;
rgb_t custom_color;

void rainbow_task(void *arg) {
    float hue = 0.0f;
    float step = 360.0f / 32.0f; // 32 steps for full rainbow
    int direction = 1;           // 1: forward, -1: backward

    while (rainbow_active) {
        // Calculate RGB from HSV (S=1, V=1 max brightness)
        rgb_t color = hsv2rgb(hue, 1.0f, 1.0f);
        setAllLED( color );

        hue += direction * step;
        if (hue >= 360.0f) {
            hue = 360.0f;
            direction = -1;
        } else if (hue <= 0.0f) {
            hue = 0.0f;
            direction = 1;
        }

        vTaskDelay(pdMS_TO_TICKS( 1500));
    }
	rainbow_active = false;
    rainbow_task_handle = NULL;
    vTaskDelete(NULL);
} // rainbow_task

void start_rainbow(void) {
    if (rainbow_active) return; // Already running
    rainbow_active = true;
    xTaskCreate(rainbow_task, "rainbow_task", 2048, NULL, 5, &rainbow_task_handle);
} // start_rainbow

void stop_rainbow(void) {
    if (!rainbow_active) return;
    rainbow_active = false;
    while (rainbow_task_handle != NULL) {
      vTaskDelay(pdMS_TO_TICKS(10));
    }
} // stop_rainbow
/*==================*/
void profile_task(void *arg) {
	int i = 0;
	int duration = 0;
    while (profile_active) {
        duration = 1000 * led_states[i].duration_sec;        
		setProfileN(i);
        vTaskDelay(pdMS_TO_TICKS( duration ));
        i = (i + 1) % led_states_count;
    }
    vTaskDelete(NULL);
} // profile_task

void start_profile(void) {
    if (profile_active) return;
    profile_active = true;
    xTaskCreate(profile_task, "profile_task", 2048, NULL, 5, &profile_task_handle);
} // start_profile

void stop_profile(void) {
    if (!profile_active) return;
    profile_active = false;
    if (profile_task_handle != NULL) {
        vTaskDelay(pdMS_TO_TICKS(200)); // Wait a bit for task to exit loop
        profile_task_handle = NULL;
    }
} // stop_profile
//-----------------
void LAMP_turn_On(void){
    LAMP_turn_Off();
	total_seconds = current_duration;
	if ( white == lamp_state ) {
	  ESP_LOGI(TAG, "white");
	  fade_in_warm_white( brightness );
	} else if ( custom == lamp_state ) {
	  ESP_LOGI(TAG, "custom\nred=%d",custom_color.red);
      setAllLED( custom_color );
	} else if ( rainbow == lamp_state ) {
	  ESP_LOGI(TAG, "rainbow");
	  start_rainbow();
	} else if ( profile == lamp_state ) {
	  ESP_LOGI(TAG, "profile");
	  start_profile();
	} else if ( profileN == lamp_state ) {
	  ESP_LOGI(TAG, "profileN");
	  ESP_LOGI(TAG, "profileN>%d<>%d<>%d", brightness, led_states_count, ( brightness-1) % (led_states_count));
	  setProfileN( ( brightness-1) % (led_states_count));
	} else {
	  ESP_LOGE(TAG, "unknown lamp_state");
	};
	LAMP_on = true;
}; // LAMPon

void LAMP_turn_Off( void ){
  total_seconds = 0;
  stop_rainbow();
  stop_profile();
  offAllLED();
  LAMP_on = false;
} // LAMP_turn_Off

void write_config_file(void) {
    FILE *f = fopen(CONFIG_FILE, "w");
    if (f == NULL) {
        ESP_LOGE(TAG, "Failed to open file for writing");
        return;
    }
    fprintf(f, "brightness=%d\nduration=%d\n", brightness, current_duration );
    fprintf(f, "red=%d\ngreen=%d\nblue=%d\n", custom_color.red, custom_color.green, custom_color.blue);
    ESP_LOGI(TAG, "Config written:\n brightness=%d\nduration=%d\n", brightness, current_duration);
	fprintf(f, "lamp_state=%d\n",(int)lamp_state);
	fprintf(f, "lamp_start=%d\n",lamp_start_on);
    fclose(f);
} // write_config_file

void read_config_file(void) {
	ESP_LOGI(TAG, "read_config_file");
    FILE *f = fopen(CONFIG_FILE, "r");
    if (f == NULL) {
        ESP_LOGW(TAG, "File not found, using defaults");
        current_duration = 5;
        brightness = 3;
        write_config_file();
        return;
    }

    char line[64];

    while (fgets(line, sizeof(line), f)) {
		int tmp;
        if (sscanf(line, "brightness=%d", &brightness) == 1) {                       // if (sscanf(line, "max_time=%" SCNu32, &max_time) == 1) {
            ESP_LOGI(TAG, "Read brightness=%d", brightness);                         //     ESP_LOGI(TAG, "Read max_time=%" PRIu32, max_time);

        } else if (sscanf(line, "red=%hhu", &custom_color.red) == 1) {
            ESP_LOGI(TAG, "Read red=%d", custom_color.red);
        } else if (sscanf(line, "green=%hhu", &custom_color.green) == 1) {
            ESP_LOGI(TAG, "Read green=%d", custom_color.green);
        } else if (sscanf(line, "blue=%hhu", &custom_color.blue) == 1) {
            ESP_LOGI(TAG, "Read blue=%d", custom_color.blue);
        } else if (sscanf(line, "duration=%d", &current_duration) == 1) {
            ESP_LOGI(TAG, "Read current_duration=%d", current_duration);
        } else if (sscanf(line, "lamp_state=%d", &tmp) == 1) {
			lamp_state = (LAMP_state_t)tmp;
            ESP_LOGI(TAG, "Read lamp_state=%d", lamp_state);
        } else if (sscanf(line, "lamp_start=%d", &lamp_start_on) == 1) {
            ESP_LOGI(TAG, "Read lamp_strt=%d", lamp_start_on);
        }
    } // while
    fclose(f);
} // read_config_file


void process_color_component(cJSON *json, const char *field_name, uint8_t *color_component, const char *color_name) {
    cJSON *item = cJSON_GetObjectItem(json, field_name);
    if (item != NULL) {
        if (cJSON_IsNumber(item)) {
            *color_component = item->valueint;
            ESP_LOGI(TAG, "%s set to %d", color_name, *color_component);
        } else {
            const char *str_val = item->valuestring;
            ESP_LOGE(TAG, "%s is not a number: %s", color_name, str_val);
            *color_component = 0;
        }
    } else {
        ESP_LOGW(TAG, "%s item not found in JSON, using default value", color_name);
    }
} // process_color_component

void lamp_settings_from_json(cJSON *json) {
    cJSON *duration_item = cJSON_GetObjectItem(json, "duration");
    if (duration_item != NULL) {
        if (cJSON_IsNumber(duration_item)) {
            current_duration = duration_item->valueint * 60; // minutes to seconds
        } else {
            const char *dstr = duration_item->valuestring;
            ESP_LOGE(TAG, "Duration is not a number: %s", dstr);
        }
    } else {
        ESP_LOGE(TAG, "Duration item not found in JSON");
    }

    cJSON *mode_item = cJSON_GetObjectItem(json, "mode");
    if (mode_item != NULL && mode_item->type == cJSON_String) {
        const char *mode = mode_item->valuestring;
        if        (strcmp(mode, "white")    == 0) { lamp_state = white;
        } else if (strcmp(mode, "custom")   == 0) { lamp_state = custom;
        } else if (strcmp(mode, "rainbow")  == 0) { lamp_state = rainbow;
        } else if (strcmp(mode, "profile")  == 0) { lamp_state = profile;
        } else if (strcmp(mode, "profileN") == 0) { lamp_state = profileN;
        } else {
            ESP_LOGE(TAG, "Unknown mode: %s", mode);
        }
    }

    process_color_component(json, "red",   &custom_color.red,   "red");
    process_color_component(json, "green", &custom_color.green, "green");
    process_color_component(json, "blue",  &custom_color.blue,  "blue");

    cJSON *brightness_item = cJSON_GetObjectItem(json, "brightness");
    if (brightness_item != NULL) {
        if (cJSON_IsNumber(brightness_item)) {
            brightness = brightness_item->valueint;
        } else if (cJSON_IsString(brightness_item)) {
            brightness = atoi(brightness_item->valuestring);
        } else {
            ESP_LOGE(TAG, "Brightness is not a number or string");
        }
    } // brightness_item
	
    cJSON *lampstart_item = cJSON_GetObjectItem(json, "lampstart");
    if (lampstart_item != NULL) {
        if (cJSON_IsNumber(lampstart_item)) {
            lamp_start_on = lampstart_item->valueint;
//        } else if (cJSON_IsString(lampstart_item)) {
//            brightness = atoi(lampstart_item->valuestring);
        } else {
            ESP_LOGE(TAG, "Brightness is not a number or string");
        }
    } // lampstart_item

    write_config_file();
    LAMP_turn_On();

    ESP_LOGI(TAG, "Settings applied - Brightness: %d, Duration: %d seconds", 
             brightness, current_duration);
}

int lamp_settings_json(char *buffer, size_t size) {
    return snprintf(buffer, size,
                    "{\"timer\": %d, \"red\": %d, \"green\": %d, \"blue\": %d, \"brightness\": %d, \"state\": %d}",
                    total_seconds, custom_color.red, custom_color.green, 
                    custom_color.blue, brightness, lamp_state);
} // lamp_settings_json

static void free_led_states(void) {
    if (led_states) {
        free(led_states);
        led_states = NULL;
    }
    led_states_count = 0;
} // free_led_states

bool load_led_states_from_cfg(void) {
    free_led_states();

    FILE *f = fopen(sequence_filename, "r");
    if (!f) {
        ESP_LOGE(TAG, "Failed to open sequence file: %s", sequence_filename);
        return false;
    }

    int tmp1 = 0;
    int tmp2 = 0;
    int tmp3 = 0;
    int tmp4 = 0;

//    if (fscanf(f, "%d\n", &led_states_count) != 1) {
	 if (fscanf(f, "%d,%d,%d,%d,%d;", &led_states_count, &tmp1, &tmp2, &tmp3, &tmp4) != 5) {
        ESP_LOGE(TAG, "Failed to read states count");
        fclose(f);
        return false;
    }
    ESP_LOGI(TAG, "Parsed values: count=%d, tmp1=%d, tmp2=%d, tmp3=%d, tmp4=%d", led_states_count, tmp1, tmp2, tmp3, tmp4);

    if (led_states_count <= 0) {
        ESP_LOGE(TAG, "Invalid states count: %d", led_states_count);
        fclose(f);
        return false;
    }
    int c;
    c = fgetc(f); // ESP_LOGI(TAG, "1?%d<>%d", c, '\r');
    c = fgetc(f); // ESP_LOGI(TAG, "2?%d<>%d", c, '\n');
/*
    while ((c = fgetc(f)) != '\n' && c != EOF) {
    }
*/
    ESP_LOGI(TAG, "next line------------------");

    led_states = malloc(sizeof(led_state_t) * led_states_count);
    if (!led_states) {
        ESP_LOGE(TAG, "Failed to allocate led_states");
        fclose(f);
        return false;
    }

    char linebuf[2048];

    for (int i = 0; i < led_states_count; i++) {
        if (!fgets(linebuf, sizeof(linebuf), f)) {
            ESP_LOGE(TAG, "Unexpected EOF reading state %d", i);
            free_led_states();
            fclose(f);
            return false;
        }

        // Parsing color
        //  duration_sec, {R,G,B}, {R,G,B}, ..., {R,G,B}
        // 5, {255,0,0}, {0,255,0}, ..., {255,255,255}

        char *p = linebuf;
        int duration_sec = 0;
//		int tmp1 = 0;
//        int tmp2 = 0;
//        ESP_LOGI(TAG, ">>>>>>>%s", p);
        int n = 0;
        if (sscanf(p, "%u%n", &duration_sec, &n) != 1) {
            ESP_LOGE(TAG, "Failed to read duration in line %d<---->%d<>>>>%s", i, n, p);
            free_led_states();
            fclose(f);
            return false;
        }
        p += n;

        while (*p == ' ' || *p == ',') p++;

        led_states[i].duration_sec = duration_sec;

        for (int led_i = 0; led_i < MAX_LED_NUMBERS; led_i++) {
            int r, g, b;

            if (sscanf(p, "{%u,%u,%u}", &r, &g, &b) != 3) { // "{%d,%d,%d}"
                ESP_LOGE(TAG, "Parsing color %d failed on line %d", led_i, i);
                free_led_states();
                fclose(f);
                return false;
            }

            led_states[i].leds[led_i].red   = (uint8_t)r;
            led_states[i].leds[led_i].green = (uint8_t)g;
            led_states[i].leds[led_i].blue  = (uint8_t)b;

            char *brace_end = strchr(p, '}');
            if (!brace_end) {
                ESP_LOGE(TAG, "Malformed color %d on line %d", led_i, i);
                free_led_states();
                fclose(f);
                return false;
            }
            p = brace_end + 1;
            while (*p == ' ' || *p == ',') p++;
        } //for led_i < MAX_LED_NUMBERS
    } // for i < led_states_count
    fclose(f);
/*
    ESP_LOGI(TAG, "++++++++++++++++++++++++++++++++++++++++++++++");
    ESP_LOGI(TAG, "Loaded %d led states from text file", led_states_count);
    int k = 0;
    ESP_LOGI(TAG, "%d>>%d rgb> %d %d %d", k, led_states[k].duration_sec, led_states[k].leds[0].red, led_states[k].leds[0].green, led_states[k].leds[0].blue);
	k++;
    ESP_LOGI(TAG, "%d>>%d rgb> %d %d %d", k, led_states[k].duration_sec, led_states[k].leds[0].red, led_states[k].leds[0].green, led_states[k].leds[0].blue);
	k++;
    ESP_LOGI(TAG, "%d>>%d rgb> %d %d %d", k, led_states[k].duration_sec, led_states[k].leds[0].red, led_states[k].leds[0].green, led_states[k].leds[0].blue);
	k++;
    ESP_LOGI(TAG, "%d>>%d rgb> %d %d %d", k, led_states[k].duration_sec, led_states[k].leds[0].red, led_states[k].leds[0].green, led_states[k].leds[0].blue);
*/
    return true;
} // load_led_states_from_cfg

// - - - - -
void LAMP_init(void){
    total_seconds = 0;
	read_config_file();
	load_led_states_from_cfg();
	initWS2812();
    if ( 1 == lamp_start_on ) {
      LAMP_turn_On();
    } else {
  	  fade_in_warm_white( 3 );
      vTaskDelay( 200 );
	  offAllLED();
	};
}; // LAMP_init

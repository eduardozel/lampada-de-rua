// script.js - Логика для взаимодействия с ESP32 WebSocket

// if (redInput && document.activeElement !== redInput) {

		let redInput, greenInput, blueInput;

		let pendingApply = false;

		let redChanged   = false;
		let greenChanged = false;
		let blueChanged  = false;

//      var gateway = 'ws://' + window.location.hostname + '/ws';
		var gateway = `ws://${window.location.hostname}/ws`;
        var websocket;
		
		let onMessageTimeout;
		
        window.addEventListener('load', onLoad);

		function showStatusMessage(text, duration = 5000) {
			const statusDiv = document.getElementById('statusMessage');
			if (!statusDiv) return;
			statusDiv.innerText = text;
			statusDiv.style.display = 'block';
			setTimeout(() => {statusDiv.style.display = 'none';}, duration);
		} // showStatusMessage

        function resetOnMessageTimeout() {
          clearOnMessageTimeout();
          onMessageTimeout = setTimeout(() => {
              showStatusMessage("not connected");
          }, 15000); // 15 секунд без сообщений
        } // resetOnMessageTimeout

        function clearOnMessageTimeout() {
          if (onMessageTimeout) {
            clearTimeout(onMessageTimeout);
            onMessageTimeout = null;
          } 
        } // clearOnMessageTimeout

        function initWebSocket() {
            console.log('Trying to open a WebSocket connection...');
            websocket = new WebSocket(gateway);
            websocket.onopen    = onOpen;
            websocket.onclose   = onClose;
            websocket.onmessage = onMessage;
        }
        function onOpen(event) {
            console.log('Connection opened');
			getState();
			resetOnMessageTimeout();
        }
        function onClose(event) {
            console.log('Connection closed');
			clearOnMessageTimeout();
            setTimeout(initWebSocket, 2000);
        }
        function onMessage(event) {
            console.log(event.data);
			resetOnMessageTimeout();
			var data = JSON.parse(event.data);

			const cnt = data.timer;
			if ( cnt > 0 ) {
				const min = Math.floor( cnt / 60);
				const sec = cnt - min * 60;
				document.getElementById('counter').innerText = min + ':' + String(sec).padStart(2, '0');
			} else {
				document.getElementById('counter').innerText = '- - -';
			}

			var red   = parseInt(data.red,   10) || 0;
			var green = parseInt(data.green, 10) || 0;
			var blue  = parseInt(data.blue,  10) || 0;

        // range 0-255
            red   = Math.max(0, Math.min(255, red));
			green = Math.max(0, Math.min(255, green));
			blue  = Math.max(0, Math.min(255, blue));

//			if (redInput)   redInput.value   = red;
//			if (greenInput) greenInput.value = green;
//			if (blueInput)  blueInput.value  = blue;

			// Проверка и автообновление, если пользователь не редактировал
			if (redInput   && !redChanged)   redInput.value   = data.red;
			if (greenInput && !greenChanged) greenInput.value = data.green;
			if (blueInput  && !blueChanged)  blueInput.value  = data.blue;

			if (pendingApply) { // Если было отправлено start и сервер прислал ответ — сбрасываем флаги
				redChanged   = false;
				greenChanged = false;
				blueChanged  = false;
				pendingApply = false;
			}
        } // onMessage(

        function onLoad(event) {
            showStatusMessage("onLoad");showStatusMessage("onLoad");
            initWebSocket();
            initButton();
            setInterval(getState, 10000);
			
			redInput   = document.getElementById('red');
			greenInput = document.getElementById('green');
			blueInput  = document.getElementById('blue');

			if (redInput) {
				redInput.addEventListener('input', ()   => { redChanged   = true;});
			} // if (redInput)

			if (greenInput) {
				greenInput.addEventListener('input', () => { greenChanged = true; });
			} // if (greenInput)

			if (blueInput) {
				blueInput.addEventListener('input', ()  => { blueChanged  = true; });
			} // if (blueInput)
				
			toggleColors();
		} // onLoad

        function getState() {
            websocket.send(JSON.stringify({ act: 'getState'}));
        }

        function initButton() {
            document.getElementById('start').addEventListener('click', btn_start);
            document.getElementById('stop').addEventListener('click',  btn_stop);
            document.getElementById('b_toggle').addEventListener('click',  btn_toggle);
            document.getElementById('mode').addEventListener('change', toggleColors);
            document.getElementById('upload').addEventListener('click', btn_upload);
		} // initButton()

        function btn_start() {
			var red   = parseInt(redInput.value,   10) || 0;
			var green = parseInt(greenInput.value, 10) || 0;
			var blue  = parseInt(blueInput.value,  10) || 0;

        // range 0-255
            red   = Math.max(0, Math.min(255, red));
			green = Math.max(0, Math.min(255, green));
			blue  = Math.max(0, Math.min(255, blue));

			redInput.value   = red;
			greenInput.value = green;
			blueInput.value  = blue;
			
            const brightness = document.getElementById('brightness').value;
			const duration = ( parseInt(document.getElementById('hr').value,   10) || 0 ) + Number(document.getElementById('mn').value);
            const mode = document.getElementById('mode').value;
            const lamp_start = document.getElementById('lampstart').value;

			
			const payload = {
				act: 'start',
				duration: duration,
				mode: mode,
                lstart: lamp_start,
				brightness: brightness,
				red:   red,
				green: green,
				blue:  blue
			};
//showStatusMessage("btn_start websocket");
			websocket.send(JSON.stringify(payload));

//            websocket.send(JSON.stringify({ act: 'start', brightness, duration, mode, red, green, blue }));
			redChanged   = false;
			greenChanged = false;
			blueChanged  = false;
			pendingApply = true; // Ожидаем подтверждение от сервера

        } // btn_start

        function btn_stop() {
			showStatusMessage("btn_STOP");
            websocket.send(JSON.stringify({ act: 'stop' }));
        } // btn_stop

        function btn_toggle() {
			showStatusMessage("btn_Toggle");
            websocket.send(JSON.stringify({ act: 'togglelight' }));
            const toggleButton = document.getElementById('b_toggle');
            toggleButton.disabled = true;
            setTimeout(() => {
              toggleButton.disabled = false;
            }, 5000);
        } // btn_stop
		
		function toggleColors() {
            var modeSelect = document.getElementById('mode');
            var customRow  = document.getElementById('colors');

/*
    if (modeSelect && customRow) {
        customRow.style.display = (modeSelect.value === 'custom') ? '' : 'none';
    }		
*/

			if (modeSelect.value === 'custom') {
				colors.style.display = '';  // Показать (сброс display к дефолтному)
				brightnesstr.style.display = 'none';
			} else {
				colors.style.display = 'none';
				brightnesstr.style.display = '';
			}
    } // toggleColors

function btn_upload() {
    var fileInput = document.getElementById('file');
    var file = fileInput.files[0];
    if (!file) {
        showStatusMessage('Please select a file', 3000);
        return;
    }
    if (!file.name.endsWith('.cfg') && !file.name.endsWith('.txt')) {
        showStatusMessage('Only .cfg or .txt files are allowed', 3000);
        return;
    }
    var reader = new FileReader();
    reader.onload = function(e) {
        fetch('/upload', {
            method: 'POST',
            body: e.target.result,
            headers: { 'Content-Type': 'text/plain' }
        }).then(response => {
            if (response.ok) {
                return response.text();
            } else {
                throw new Error('Upload failed with status: ' + response.status);
            }
        }).then(data => {
            console.log(data); // "File uploaded successfully"
            showStatusMessage('Upload successful: ' + data, 5000);
            fileInput.value = ''; // Очистить поле ввода после успешной загрузки
        }).catch(error => {
            console.error('Upload failed:', error);
            showStatusMessage('Upload failed: ' + error.message, 5000);
        });
    };
    reader.readAsText(file);
} // btn_upload

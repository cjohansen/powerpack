(function () {
  var powerpack = document.createElement("div");
  document.body.appendChild(powerpack);

  function renderHud({markup}) {
    powerpack.innerHTML = markup;
  }

  function connect() {
    var source = new EventSource("{{route}}?hash={{hash}}&uri=" + location.pathname);

    source.onmessage = function (m) {
      if (m.data) {
        var payload = JSON.parse(m.data);
        console.log("Dev stream payload", payload);

        if (payload.action == "reload") {
          location.reload(true);
        } else if (payload.action == "render-hud") {
          renderHud(payload);
        }
      } else {
        console.log("Event source message contained no data");
      }
    };

    source.onerror = function (m) {
      console.log("Dev stream connection error", m);
    };
  }

  connect();
}());

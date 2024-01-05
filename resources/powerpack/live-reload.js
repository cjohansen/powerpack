(function () {
  var powerpack = document.createElement("div");
  document.body.appendChild(powerpack);

  function renderHud({markup}) {
    powerpack.innerHTML = markup;
    if (typeof powerpackPrism != "undefined") {
      powerpackPrism.highlightAllUnder(powerpack);
    }
  }

  function reloadCSS(oldPath, newPath) {
    Array.from(document.querySelectorAll("link"))
      .filter(link => link.getAttribute("path") === oldPath)
      .map(link => link.href = newPath);
  }

  function connect() {
    var source = new EventSource("{{route}}?hash={{hash}}&uri=" + location.pathname);

    source.onmessage = function (m) {
      if (m.data) {
        var payload = JSON.parse(m.data);
        console.log("Dev stream payload", payload);

        if (payload.action == "reload") {
          location.reload(true);
        } else if (payload.action == "reload-css") {
          reloadCSS(payload.path, payload.updatedPath);
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

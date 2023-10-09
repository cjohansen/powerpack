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

  function initToggler() {
    document.body.addEventListener("click", function (e) {
      if (e.target.classList && e.target.classList.contains("pp-toggle-button")) {
        e.preventDefault();
        e.stopPropagation();

        var container = e.target.closest(".pp-toggle");

        if (container.classList.contains("pp-toggle-collapsed")) {
          container.classList.remove("pp-toggle-collapsed");
        } else {
          container.classList.add("pp-toggle-collapsed");
        }
      }
    });
  }


  connect();
  initToggler();
}());

function connect() {
  var source = new EventSource("{{route}}?hash={{hash}}&uri=" + location.pathname);

  source.onmessage = function (m) {
    if (m.data) {
      var payload = JSON.parse(m.data);
      console.log("Live reload payload", payload);

      if (payload.action == "reload") {
        location.reload(true);
      }
    } else {
      console.log("Event source message contained no data");
    }
  };

  source.onerror = function (m) {
    console.log("Live reload connection error", m);
  };
}

connect();

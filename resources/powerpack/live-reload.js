function connect() {
  var source = new EventSource("{{route}}?hash={{hash}}&uri=" + location.pathname);

  source.onmessage = function (m) {
    if (m.data) {
      console.log("Live reload payliad", JSON.parse(m.data));
    } else {
      console.log("Event source message contained no data");
    }

    location.reload(true);
  };

  source.onerror = function (m) {
    console.log("Live reload connection error", m);
  };
}

connect();

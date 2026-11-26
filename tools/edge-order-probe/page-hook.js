(() => {
  const endpoint = "/mangkhut/mms/recentOrderList";
  const send = (requestBody, status, responseBody) => {
    window.postMessage({
      source: "zh伴-edge-order-probe",
      requestBody,
      status,
      responseBody,
      capturedAt: Date.now()
    }, location.origin);
  };
  const bodyText = (body) => {
    if (typeof body === "string") return body;
    if (body instanceof URLSearchParams) return body.toString();
    return "";
  };
  const parse = (value) => {
    try { return JSON.parse(value || "{}"); } catch { return {}; }
  };

  const originalFetch = window.fetch;
  window.fetch = async function(input, init) {
    const response = await originalFetch.apply(this, arguments);
    const url = typeof input === "string" ? input : input?.url || "";
    if (url.includes(endpoint)) {
      response.clone().json().then((data) => send(parse(bodyText(init?.body)), response.status, data))
        .catch(() => send(parse(bodyText(init?.body)), response.status, {}));
    }
    return response;
  };

  const originalOpen = XMLHttpRequest.prototype.open;
  const originalSend = XMLHttpRequest.prototype.send;
  XMLHttpRequest.prototype.open = function(method, url) {
    this.__zhOrderUrl = String(url || "");
    return originalOpen.apply(this, arguments);
  };
  XMLHttpRequest.prototype.send = function(body) {
    if (this.__zhOrderUrl?.includes(endpoint)) {
      this.addEventListener("loadend", () => {
        let response = {};
        try {
          response = typeof this.response === "object" && this.response !== null
            ? this.response : JSON.parse(this.responseText || "{}");
        } catch { }
        send(parse(bodyText(body)), this.status, response);
      }, { once: true });
    }
    return originalSend.apply(this, arguments);
  };
})();

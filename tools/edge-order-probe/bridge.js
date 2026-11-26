window.addEventListener("message", (event) => {
  if (event.source !== window || event.origin !== location.origin
      || event.data?.source !== "zh伴-edge-order-probe") return;
  chrome.runtime.sendMessage({ type: "order-response", payload: event.data });
});

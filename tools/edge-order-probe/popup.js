chrome.storage.session.get("latestOrderProbe", ({ latestOrderProbe }) => {
  const state = document.getElementById("state");
  const detail = document.getElementById("detail");
  if (!latestOrderProbe) {
    state.textContent = "尚未捕获请求。打开拼多多订单列表并手动查询一次。";
    detail.textContent = "扩展只观察页面已发出的请求，不会自动刷新或查询。";
    return;
  }
  const item = latestOrderProbe;
  state.className = item.success ? "ok" : "bad";
  state.textContent = item.success ? "平台返回成功" : `平台返回失败${item.error_code == null ? "" : `（${item.error_code}）`}`;
  detail.textContent = JSON.stringify({
    http_status: item.http_status,
    success: item.success,
    error_code: item.error_code,
    error_message: item.error_message,
    total: item.total,
    returned: item.returned,
    captured_at: new Date(item.captured_at).toLocaleString("zh-CN"),
    request: item.request,
    row_fields: item.row_fields,
    sample_safe_fields: item.sample_safe_fields,
    nested_shapes: item.nested_shapes
  }, null, 2);
});

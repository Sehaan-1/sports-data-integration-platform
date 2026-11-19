export const $ = (selector, root = document) => root.querySelector(selector);
export const $$ = (selector, root = document) => [
  ...root.querySelectorAll(selector),
];
export const esc = (value) =>
  String(value ?? "").replace(
    /[&<>"']/g,
    (char) =>
      ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" })[
        char
      ],
  );
export const icon = (name, extra = "") =>
  `<svg class="icon ${esc(extra)}" aria-hidden="true"><use href="./assets/icons.svg#${esc(name)}"/></svg>`;
export const formatNumber = (value, decimals = 0) =>
  value == null || !Number.isFinite(Number(value))
    ? "—"
    : new Intl.NumberFormat("en-US", {
        maximumFractionDigits: decimals,
        minimumFractionDigits: decimals,
      }).format(value);
export const formatDate = (value, options = {}) =>
  value
    ? new Intl.DateTimeFormat("en-US", {
        month: "short",
        day: "numeric",
        timeZone: "UTC",
        ...options,
      }).format(new Date(value.length === 10 ? `${value}T12:00:00Z` : value))
    : "—";
export const formatDateTime = (value) =>
  value
    ? formatDate(value, {
        month: "short",
        day: "numeric",
        hour: "2-digit",
        minute: "2-digit",
        hour12: false,
      })
    : "Not yet synced";
export function relativeTime(value) {
  if (!value) return "Not yet synced";
  const minutes = Math.max(
    0,
    Math.floor((Date.now() - new Date(value).getTime()) / 60_000),
  );
  if (minutes < 1) return "Just now";
  if (minutes < 60) return `${minutes} min ago`;
  if (minutes < 1440) return `${Math.floor(minutes / 60)} hr ago`;
  if (minutes < 2880) return "Yesterday";
  return formatDate(value);
}
export function initials(name) {
  return String(name || "SDIP")
    .trim()
    .split(/\s+/)
    .filter(Boolean)
    .slice(0, 2)
    .map((part) => part[0])
    .join("")
    .toUpperCase();
}
export function avatar(name, extra = "") {
  const colors = [
    ["#f3e9de", "#82603c"],
    ["#e7eee9", "#456c53"],
    ["#e8eaf2", "#5c6787"],
    ["#f3e6e4", "#875a55"],
    ["#e4edf0", "#476d7c"],
    ["#efece2", "#75663d"],
  ];
  const hash = [...String(name)].reduce(
    (sum, char) => sum + char.charCodeAt(0),
    0,
  );
  const [background, color] = colors[hash % colors.length];
  return `<span class="avatar ${esc(extra)}" style="background:${background};color:${color}" aria-hidden="true">${esc(initials(name))}</span>`;
}
export function statusTag(label, tone = "", symbol = null) {
  return `<span class="status-tag ${esc(tone)}">${symbol ? icon(symbol) : '<span class="tiny-dot"></span>'}${esc(label)}</span>`;
}
export function emptyState(
  title,
  description,
  { symbol = "database", action = "", success = false } = {},
) {
  return `<div class="empty-state${success ? " success" : ""}"><span class="empty-icon">${icon(symbol)}</span><h3>${esc(title)}</h3><p>${esc(description)}</p>${action}</div>`;
}
export function loadingPanel(label = "Loading workspace data…") {
  return `<div class="loading-panel">${icon("loader-circle")}<span>${esc(label)}</span></div>`;
}
export function saveBlob(blob, filename) {
  const url = URL.createObjectURL(blob);
  const anchor = document.createElement("a");
  anchor.href = url;
  anchor.download = filename;
  document.body.append(anchor);
  anchor.click();
  anchor.remove();
  setTimeout(() => URL.revokeObjectURL(url), 1000);
}
export function debounce(callback, delay = 120) {
  let timer;
  const wrapped = (...args) => {
    clearTimeout(timer);
    timer = setTimeout(() => callback(...args), delay);
  };
  wrapped.cancel = () => clearTimeout(timer);
  return wrapped;
}


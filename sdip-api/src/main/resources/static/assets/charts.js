import { esc, formatNumber, formatDate, icon } from "./ui.js";

export function curvePath(points) {
  return points
    .map((point, index) => {
      if (!index) return `M${point[0].toFixed(2)},${point[1].toFixed(2)}`;
      const previous = points[index - 1];
      const middle = ((point[0] + previous[0]) / 2).toFixed(2);
      return `C${middle},${previous[1].toFixed(2)} ${middle},${point[1].toFixed(2)} ${point[0].toFixed(2)},${point[1].toFixed(2)}`;
    })
    .join(" ");
}

export function sparkline(values, color = "#e9946e", fill = false) {
  if (!values.length) return "";
  const width = 72,
    height = 28,
    max = Math.max(1, ...values),
    min = Math.min(...values) * 0.7;
  const points = values.map((value, index) => [
    2 + (index / Math.max(1, values.length - 1)) * (width - 4),
    height - 3 - ((value - min) / (max - min || 1)) * (height - 7),
  ]);
  const path = curvePath(points);
  return `<svg class="stat-spark" viewBox="0 0 ${width} ${height}" aria-hidden="true">${fill ? `<path d="${path} L${width - 2},${height} L2,${height} Z" fill="${color}" opacity=".09"/>` : ""}<path d="${path}" stroke="${color}" stroke-width="1.7" fill="none" stroke-linecap="round" stroke-linejoin="round"/></svg>`;
}

const niceMax = (value) => {
  if (value <= 0) return 4;
  const step = value / 4;
  const magnitude = 10 ** Math.floor(Math.log10(step));
  return Math.ceil(step / magnitude) * magnitude * 4;
};
const tickValue = (value) =>
  value === 0
    ? "0"
    : value >= 10000
      ? `${formatNumber(value / 1000, value % 1000 ? 1 : 0)}k`
      : value >= 1000
        ? formatNumber(value)
        : value < 2
          ? formatNumber(value, 2)
          : formatNumber(value);
let chartSequence = 0;

/** Responsive, keyboard-inspectable SVG. Values remain ordinary data, not an image or canvas.
 * The right axis is independent for ForceDecks RSI; it is never silently rescaled into AU. */
export function mountLineChart(container, config) {
  if (!container) return () => {};
  const id = `sdip-chart-${++chartSequence}`;
  const {
    labels = [],
    series = [],
    title = "Workload chart",
    height = 167,
  } = config;
  let activeIndex = labels.length - 1;
  let chartInfo;
  let frame;
  const hasData = series.some((item) =>
    item.values.some((value) => value != null && value !== 0),
  );
  if (!hasData) {
    container.innerHTML = `<div class="chart-empty" style="min-height:${height}px;display:flex;align-items:center;justify-content:center;gap:8px;color:var(--muted-dark);font-size:12px">${icon("chart-line")}<span>${esc(config.emptyText || "No training data in this period.")}</span></div>`;
    return () => {};
  }

  function render() {
    const width = Math.max(230, Math.floor(container.clientWidth));
    const dualAxis = series.some((item) => item.axis === "right");
    const pad = {
      left: width < 400 ? 32 : 37,
      right: dualAxis ? 35 : 9,
      top: 10,
      bottom: 25,
    };
    const plotWidth = width - pad.left - pad.right;
    const plotHeight = height - pad.top - pad.bottom;
    const maxLeft = niceMax(
      Math.max(
        0,
        ...series
          .filter((item) => item.axis !== "right")
          .flatMap((item) => item.values.filter((value) => value != null)),
      ),
    );
    const maxRight = niceMax(
      Math.max(
        0,
        ...series
          .filter((item) => item.axis === "right")
          .flatMap((item) => item.values.filter((value) => value != null)),
      ),
    );
    const x = (index) =>
      pad.left +
      (labels.length < 2 ? 0.5 : index / (labels.length - 1)) * plotWidth;
    const y = (value, axis) =>
      pad.top +
      plotHeight -
      (Math.max(0, value) / (axis === "right" ? maxRight : maxLeft)) *
        plotHeight;
    let content = `<svg class="data-chart" style="height:${height}px" viewBox="0 0 ${width} ${height}" role="img" tabindex="0" aria-label="${esc(title)}. Use the left and right arrow keys to inspect daily values."><title>${esc(title)}</title><defs><linearGradient id="${id}-fill" x1="0" x2="0" y1="0" y2="1"><stop offset="0%" stop-color="#ee9069" stop-opacity=".15"/><stop offset="100%" stop-color="#ee9069" stop-opacity=".015"/></linearGradient></defs>`;
    for (let tick = 0; tick <= 4; tick++) {
      const yy = pad.top + (plotHeight * tick) / 4;
      content += `<line class="grid-line" x1="${pad.left}" y1="${yy}" x2="${width - pad.right}" y2="${yy}"/><text x="${pad.left - 9}" y="${yy + 3}" text-anchor="end">${tickValue((maxLeft * (4 - tick)) / 4)}</text>`;
      if (dualAxis)
        content += `<text x="${width - pad.right + 8}" y="${yy + 3}" text-anchor="start" style="fill:#507760">${tickValue((maxRight * (4 - tick)) / 4)}</text>`;
    }
    const tickCount = width < 430 ? 4 : 7;
    const labelIndices = new Set(
      Array.from({ length: Math.min(tickCount, labels.length) }, (_, index) =>
        Math.round(
          (index / Math.max(1, Math.min(tickCount, labels.length) - 1)) *
            (labels.length - 1),
        ),
      ),
    );
    labels.forEach((day, index) => {
      if (!labelIndices.has(index)) return;
      const label =
        labels.length <= 7
          ? `${new Intl.DateTimeFormat("en-US", { weekday: "short", timeZone: "UTC" }).format(new Date(`${day}T12:00:00Z`))} ${day.slice(8)}`
          : formatDate(day, { month: "short", day: "numeric" });
      content += `<text x="${x(index)}" y="${height - 4}" text-anchor="${index === 0 ? "start" : index === labels.length - 1 ? "end" : "middle"}">${esc(label)}</text>`;
    });
    // Comparison first, so the current series is always visually foremost.
    [...series]
      .sort((a, b) => Number(Boolean(b.dashed)) - Number(Boolean(a.dashed)))
      .forEach((item) => {
        // Null is an unmeasured day, not a zero or permission to invent an interpolation.
        const segments = [];
        let points = [];
        item.values.forEach((value, index) => {
          if (value == null) {
            if (points.length) segments.push(points);
            points = [];
          } else points.push([x(index), y(value, item.axis)]);
        });
        if (points.length) segments.push(points);
        segments.forEach((points) => {
          const path = curvePath(points);
          if (item.fill)
            content += `<path d="${path} L${points.at(-1)[0]},${pad.top + plotHeight} L${points[0][0]},${pad.top + plotHeight} Z" fill="url(#${id}-fill)"/>`;
          content += `<path d="${path}" fill="none" stroke="${esc(item.color || "#e86532")}" stroke-width="${item.dashed ? 1.8 : 2.35}"${item.dashed ? ' stroke-dasharray="4 5"' : ""} stroke-linecap="round" stroke-linejoin="round"/>`;
          if (points.length === 1)
            content += `<circle cx="${points[0][0]}" cy="${points[0][1]}" r="3" fill="${esc(item.color || "#e86532")}"/>`;
        });
      });
    content += `<g class="chart-inspector" style="display:none;pointer-events:none"><line class="chart-hover-line" style="opacity:1" y1="${pad.top}" y2="${pad.top + plotHeight}"/><g class="chart-inspector-dots"></g></g></svg><div id="${id}-tooltip" class="chart-tooltip" role="tooltip" hidden></div>`;
    container.innerHTML = content;
    chartInfo = { width, x, y, pad, plotWidth };
    const svg = container.querySelector("svg");
    svg.addEventListener("pointermove", (event) => {
      const bounds = svg.getBoundingClientRect();
      const relativeX = ((event.clientX - bounds.left) * width) / bounds.width;
      activeIndex = Math.max(
        0,
        Math.min(
          labels.length - 1,
          Math.round(
            ((relativeX - pad.left) / plotWidth) * (labels.length - 1),
          ),
        ),
      );
      inspect(activeIndex);
    });
    svg.addEventListener("pointerleave", () => {
      if (document.activeElement !== svg) hide();
    });
    svg.addEventListener("focus", () => inspect(activeIndex));
    svg.addEventListener("blur", hide);
    svg.addEventListener("keydown", (event) => {
      if (
        !["ArrowLeft", "ArrowRight", "Home", "End", "Escape"].includes(
          event.key,
        )
      )
        return;
      event.preventDefault();
      if (event.key === "Escape") {
        hide();
        return;
      }
      if (event.key === "Home") activeIndex = 0;
      else if (event.key === "End") activeIndex = labels.length - 1;
      else
        activeIndex = Math.max(
          0,
          Math.min(
            labels.length - 1,
            activeIndex + (event.key === "ArrowLeft" ? -1 : 1),
          ),
        );
      inspect(activeIndex);
    });
  }

  function inspect(index) {
    const { width, x, y } = chartInfo;
    const group = container.querySelector(".chart-inspector");
    const tooltip = container.querySelector(".chart-tooltip");
    group.style.display = "";
    group.querySelector("line").setAttribute("x1", x(index));
    group.querySelector("line").setAttribute("x2", x(index));
    group.querySelector(".chart-inspector-dots").innerHTML = series
      .filter((item) => !item.dashed && item.values[index] != null)
      .map(
        (item) =>
          `<circle cx="${x(index)}" cy="${y(item.values[index], item.axis)}" r="3.5" fill="#fff" stroke="${esc(item.color || "#e86532")}" stroke-width="2"/>`,
      )
      .join("");
    tooltip.innerHTML = `<strong>${esc(formatDate(labels[index], { weekday: "short", month: "short", day: "numeric" }))}</strong>${series.map((item) => `<p><span><i class="tiny-dot" style="background:${esc(item.color || "#e86532")}"></i>${esc(item.label)}</span><b>${item.values[index] == null ? "—" : `${formatNumber(item.values[index], item.decimals || 0)} ${esc(item.unit || "")}`}</b></p>`).join("")}`;
    tooltip.hidden = false;
    const tooltipWidth = tooltip.offsetWidth;
    tooltip.style.left = `${Math.max(0, Math.min(width - tooltipWidth, x(index) - tooltipWidth / 2))}px`;
    tooltip.style.top = "0px";
    container.querySelector("svg").setAttribute("aria-describedby", tooltip.id);
  }
  function hide() {
    const inspector = container.querySelector(".chart-inspector");
    const tooltip = container.querySelector(".chart-tooltip");
    if (inspector) inspector.style.display = "none";
    if (tooltip) tooltip.hidden = true;
  }
  render();
  let lastWidth = container.clientWidth;
  const observer = new ResizeObserver(() => {
    if (Math.abs(container.clientWidth - lastWidth) < 2) return;
    lastWidth = container.clientWidth;
    cancelAnimationFrame(frame);
    frame = requestAnimationFrame(render);
  });
  observer.observe(container);
  return () => {
    cancelAnimationFrame(frame);
    observer.disconnect();
  };
}

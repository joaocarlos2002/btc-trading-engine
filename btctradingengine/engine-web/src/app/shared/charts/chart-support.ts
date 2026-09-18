import {
  CategoryScale,
  Chart,
  Filler,
  LinearScale,
  LineController,
  LineElement,
  PointElement,
  Tooltip,
} from 'chart.js';

// Chart.js is tree-shaken: register only the pieces the line charts use.
Chart.register(LineController, LineElement, PointElement, LinearScale, CategoryScale, Filler, Tooltip);

export { Chart };

/** Reads a design token (CSS custom property) as the browser resolved it for the current theme. */
export function cssVar(name: string): string {
  return getComputedStyle(document.documentElement).getPropertyValue(name).trim();
}

/** A hex token color (#rrggbb) at a given opacity, as rgba() so every canvas implementation parses it. */
export function withAlpha(color: string, alpha: number): string {
  const hex = /^#([0-9a-f]{6})$/i.exec(color)?.[1];
  if (!hex) {
    return color;
  }
  const value = Number.parseInt(hex, 16);
  return `rgba(${(value >> 16) & 255}, ${(value >> 8) & 255}, ${value & 255}, ${alpha})`;
}

export function prefersReducedMotion(): boolean {
  return window.matchMedia('(prefers-reduced-motion: reduce)').matches;
}

/**
 * Coalesces many updates into one redraw per animation frame: the socket can deliver several messages
 * between frames, and a chart only needs the latest state.
 */
export function frameScheduler(draw: () => void): { schedule(): void; cancel(): void } {
  let frame = 0;
  return {
    schedule() {
      if (!frame) {
        frame = requestAnimationFrame(() => {
          frame = 0;
          draw();
        });
      }
    },
    cancel() {
      cancelAnimationFrame(frame);
      frame = 0;
    },
  };
}

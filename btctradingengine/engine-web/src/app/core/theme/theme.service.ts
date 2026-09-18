import { DOCUMENT } from '@angular/common';
import { effect, inject, Injectable, signal } from '@angular/core';

export type Theme = 'dark' | 'light';

const STORAGE_KEY = 'bte.theme';

/** Dark or light, from the user's choice or else the system preference; kept on <html data-theme>. */
@Injectable({ providedIn: 'root' })
export class ThemeService {
  private readonly document = inject(DOCUMENT);

  readonly theme = signal<Theme>(this.initialTheme());

  constructor() {
    effect(() => {
      this.document.documentElement.dataset['theme'] = this.theme();
    });
  }

  toggle(): void {
    const next: Theme = this.theme() === 'dark' ? 'light' : 'dark';
    this.theme.set(next);
    try {
      localStorage.setItem(STORAGE_KEY, next);
    } catch {
      // Storage blocked (private mode): the choice lasts for this page only
    }
  }

  private initialTheme(): Theme {
    try {
      const stored = localStorage.getItem(STORAGE_KEY);
      if (stored === 'dark' || stored === 'light') {
        return stored;
      }
    } catch {
      // Storage blocked: fall back to the system preference
    }
    return this.document.defaultView?.matchMedia('(prefers-color-scheme: light)').matches ? 'light' : 'dark';
  }
}

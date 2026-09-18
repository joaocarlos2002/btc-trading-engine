import { inject, Injectable, signal } from '@angular/core';
import { Title } from '@angular/platform-browser';
import { RouterStateSnapshot, TitleStrategy } from '@angular/router';

export const APP_NAME = 'BTC Engine';

/** Sets the tab title from each route's `title` and exposes it to the top bar. */
@Injectable({ providedIn: 'root' })
export class PageTitleStrategy extends TitleStrategy {
  private readonly title = inject(Title);

  readonly current = signal('');

  override updateTitle(snapshot: RouterStateSnapshot): void {
    const page = this.buildTitle(snapshot) ?? '';
    this.current.set(page);
    this.title.setTitle(page ? `${page} · ${APP_NAME}` : APP_NAME);
  }
}

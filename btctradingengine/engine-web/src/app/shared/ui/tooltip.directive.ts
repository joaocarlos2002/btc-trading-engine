import { DOCUMENT } from '@angular/common';
import { Directive, ElementRef, inject, input, OnDestroy } from '@angular/core';

let nextId = 0;
const SHOW_DELAY = 280;
const GAP = 8;

/**
 * [bteTooltip]="text": a short explanation shown on hover and on keyboard focus, linked to the host with
 * aria-describedby so screen readers read it too. Escape hides it. The bubble lives on <body> so cards with
 * overflow never clip it, and flips below the host when there is no room above.
 */
@Directive({
  selector: '[bteTooltip]',
  host: {
    '(mouseenter)': 'scheduleShow()',
    '(mouseleave)': 'hide()',
    '(focusin)': 'show()',
    '(focusout)': 'hide()',
    '(keydown.escape)': 'hide()',
    '[attr.aria-describedby]': 'tooltipText() ? id : null',
  },
})
export class TooltipDirective implements OnDestroy {
  readonly tooltipText = input('', { alias: 'bteTooltip' });

  private readonly host = inject<ElementRef<HTMLElement>>(ElementRef);
  private readonly document = inject(DOCUMENT);
  protected readonly id = `tooltip-${nextId++}`;
  private bubble: HTMLElement | null = null;
  private timer: ReturnType<typeof setTimeout> | undefined;

  protected scheduleShow(): void {
    clearTimeout(this.timer);
    this.timer = setTimeout(() => this.show(), SHOW_DELAY);
  }

  protected show(): void {
    clearTimeout(this.timer);
    const text = this.tooltipText();
    if (!text) {
      return;
    }
    const bubble = this.bubble ?? this.create();
    bubble.textContent = text;
    this.position(bubble);
    requestAnimationFrame(() => bubble.classList.add('visible'));
  }

  protected hide(): void {
    clearTimeout(this.timer);
    this.bubble?.classList.remove('visible');
  }

  ngOnDestroy(): void {
    clearTimeout(this.timer);
    this.bubble?.remove();
  }

  private create(): HTMLElement {
    const bubble = this.document.createElement('div');
    bubble.id = this.id;
    bubble.setAttribute('role', 'tooltip');
    bubble.className = 'bte-tooltip';
    this.document.body.appendChild(bubble);
    this.bubble = bubble;
    return bubble;
  }

  private position(bubble: HTMLElement): void {
    const anchor = this.host.nativeElement.getBoundingClientRect();
    const view = this.document.documentElement;
    const width = bubble.offsetWidth;
    const height = bubble.offsetHeight;
    const below = anchor.top - height - GAP < 8;
    const top = below ? anchor.bottom + GAP : anchor.top - height - GAP;
    const centered = anchor.left + anchor.width / 2 - width / 2;
    const left = Math.min(Math.max(8, centered), view.clientWidth - width - 8);
    bubble.classList.toggle('below', below);
    bubble.style.top = `${Math.round(top + window.scrollY)}px`;
    bubble.style.left = `${Math.round(left + window.scrollX)}px`;
  }
}

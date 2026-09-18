import { DOCUMENT } from '@angular/common';
import {
  afterNextRender,
  ChangeDetectionStrategy,
  Component,
  computed,
  ElementRef,
  inject,
  Injector,
  signal,
  viewChild,
} from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { NavigationEnd, Router, RouterLink, RouterLinkActive, RouterOutlet } from '@angular/router';
import { filter } from 'rxjs';
import { AuthService } from '../core/auth/auth.service';
import { ThemeService } from '../core/theme/theme.service';
import { IconComponent, IconName } from '../shared/ui/icon.component';
import { ToastService } from '../shared/ui/toast';
import { TooltipDirective } from '../shared/ui/tooltip.directive';
import { ConnectionStatusComponent, UtcClockComponent } from './status.components';

interface NavItem {
  path: string;
  label: string;
  icon: IconName;
  description: string;
}

interface NavSection {
  title: string;
  items: NavItem[];
}

const COLLAPSED_KEY = 'bte.sidebar.collapsed';

/**
 * The frame of every logged-in page: sidebar navigation (collapsible on desktop, a drawer on small
 * screens), a slim top bar with the feed status and the UTC clock, and the routed page.
 */
@Component({
  selector: 'bte-shell',
  imports: [
    RouterOutlet,
    RouterLink,
    RouterLinkActive,
    IconComponent,
    TooltipDirective,
    ConnectionStatusComponent,
    UtcClockComponent,
  ],
  templateUrl: './shell.component.html',
  styleUrl: './shell.component.scss',
  host: {
    '[class.collapsed]': 'collapsed() && !isMobile()',
    '[class.drawer-open]': 'drawerOpen()',
    '(document:keydown.escape)': 'closeDrawer()',
  },
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class ShellComponent {
  protected readonly auth = inject(AuthService);
  protected readonly theme = inject(ThemeService);
  private readonly router = inject(Router);
  private readonly toasts = inject(ToastService);
  private readonly document = inject(DOCUMENT);
  private readonly injector = inject(Injector);

  protected readonly sections: NavSection[] = [
    {
      title: 'Monitoramento',
      items: [
        { path: '/live', label: 'Ao vivo', icon: 'activity', description: 'Preço, sinal, posição e trades' },
      ],
    },
    {
      title: 'Análise',
      items: [
        { path: '/backtest', label: 'Backtest', icon: 'flask', description: 'Estratégia contra o histórico' },
      ],
    },
  ];

  protected readonly collapsed = signal(this.readCollapsed());
  protected readonly drawerOpen = signal(false);
  protected readonly loggingOut = signal(false);

  protected readonly initials = computed(() => (this.auth.session()?.username ?? '?').slice(0, 2).toUpperCase());
  protected readonly roleLabel = computed(() =>
    this.auth.isTrader() ? 'Trader' : this.auth.session() ? 'Somente leitura' : '',
  );

  private readonly sidebar = viewChild.required<ElementRef<HTMLElement>>('sidebar');
  private readonly mobileQuery = this.document.defaultView?.matchMedia('(max-width: 1023px)');
  protected readonly isMobile = signal(this.mobileQuery?.matches ?? false);
  protected readonly navExpanded = computed(() => (this.isMobile() ? this.drawerOpen() : !this.collapsed()));

  constructor() {
    this.mobileQuery?.addEventListener('change', (event) => {
      this.isMobile.set(event.matches);
      this.drawerOpen.set(false);
    });
    this.router.events
      .pipe(
        filter((event) => event instanceof NavigationEnd),
        takeUntilDestroyed(),
      )
      .subscribe(() => this.drawerOpen.set(false));
  }

  /** The top-left button: a drawer on small screens, collapse/expand on desktop. */
  protected toggleNavigation(): void {
    if (this.isMobile()) {
      this.drawerOpen.update((open) => !open);
      if (this.drawerOpen()) {
        // Once rendered (no longer inert), move the focus into the drawer
        afterNextRender(
          () => this.sidebar().nativeElement.querySelector<HTMLElement>('a, button')?.focus(),
          { injector: this.injector },
        );
      }
      return;
    }
    this.collapsed.update((value) => !value);
    try {
      localStorage.setItem(COLLAPSED_KEY, String(this.collapsed()));
    } catch {
      // Storage blocked: the choice lasts for this page only
    }
  }

  protected closeDrawer(): void {
    this.drawerOpen.set(false);
  }

  protected logout(): void {
    this.loggingOut.set(true);
    this.auth.logout().subscribe({
      next: () => void this.router.navigate(['/login'], { queryParams: { reason: 'logout' } }),
      error: () => {
        this.loggingOut.set(false);
        this.toasts.error('Não foi possível sair', 'Tente novamente em alguns segundos.');
      },
    });
  }

  private readCollapsed(): boolean {
    try {
      return localStorage.getItem(COLLAPSED_KEY) === 'true';
    } catch {
      return false;
    }
  }
}

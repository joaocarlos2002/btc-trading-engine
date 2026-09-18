import { HttpErrorResponse } from '@angular/common/http';
import { ChangeDetectionStrategy, Component, computed, inject, input, signal } from '@angular/core';
import { Router } from '@angular/router';
import { AuthService } from '../../core/auth/auth.service';
import { ThemeService } from '../../core/theme/theme.service';
import { SpinnerComponent } from '../../shared/ui/feedback.components';
import { IconComponent } from '../../shared/ui/icon.component';

const NOTICES: Record<string, { tone: 'info' | 'warning'; text: string }> = {
  logout: { tone: 'info', text: 'Você saiu da conta.' },
  expired: { tone: 'warning', text: 'Sua sessão expirou. Entre de novo para continuar.' },
  unreachable: { tone: 'warning', text: 'Não foi possível falar com o servidor.' },
};

/** Login with the dashboard account (dashboard.auth.*). Returns to the page that asked for it. */
@Component({
  selector: 'bte-login-page',
  imports: [IconComponent, SpinnerComponent],
  template: `
    <button
      type="button"
      class="btn btn-ghost btn-icon theme"
      [attr.aria-label]="theme.theme() === 'dark' ? 'Usar tema claro' : 'Usar tema escuro'"
      (click)="theme.toggle()"
    >
      <bte-icon [name]="theme.theme() === 'dark' ? 'sun' : 'moon'" [size]="18" />
    </button>

    <main class="wrap">
      <div class="card" animate.enter="enter-fade-up">
        <div class="brand">
          <span class="logo" aria-hidden="true">
            <svg viewBox="0 0 24 24" width="20" height="20" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
              <path d="M3 17l5-5 4 4 8-9" />
              <path d="M15 7h5v5" />
            </svg>
          </span>
          <h1>Entrar no BTC Engine</h1>
          <p>Use a conta configurada em <code>dashboard.auth</code>.</p>
        </div>

        @if (notice(); as notice) {
          <p class="notice" [class]="'notice tone-' + notice.tone" role="status">
            <bte-icon [name]="notice.tone === 'info' ? 'info' : 'alert-triangle'" [size]="16" />
            {{ notice.text }}
          </p>
        }

        <form novalidate (submit)="$event.preventDefault(); submit()">
          <div class="field">
            <label class="field-label" for="username">Usuário</label>
            <input
              id="username"
              class="input"
              name="username"
              autocomplete="username"
              autocapitalize="none"
              spellcheck="false"
              required
              [value]="username()"
              [attr.aria-invalid]="error() === 'credentials'"
              (input)="username.set($any($event.target).value)"
            />
          </div>

          <div class="field">
            <label class="field-label" for="password">Senha</label>
            <div class="password">
              <input
                id="password"
                class="input"
                name="password"
                autocomplete="current-password"
                required
                [type]="showPassword() ? 'text' : 'password'"
                [value]="password()"
                [attr.aria-invalid]="error() === 'credentials'"
                [attr.aria-describedby]="error() ? 'login-error' : null"
                (input)="password.set($any($event.target).value)"
              />
              <button
                type="button"
                class="btn btn-ghost btn-icon btn-sm reveal"
                [attr.aria-label]="showPassword() ? 'Ocultar senha' : 'Mostrar senha'"
                [attr.aria-pressed]="showPassword()"
                (click)="showPassword.set(!showPassword())"
              >
                <bte-icon [name]="showPassword() ? 'eye-off' : 'eye'" [size]="16" />
              </button>
            </div>
          </div>

          @if (errorText(); as text) {
            <p id="login-error" class="field-error error" role="alert" animate.enter="enter-fade">
              <bte-icon name="alert-circle" [size]="14" /> {{ text }}
            </p>
          }

          <button type="submit" class="btn btn-primary btn-lg btn-block" [disabled]="!canSubmit()" [attr.aria-busy]="submitting()">
            @if (submitting()) {
              <bte-spinner [size]="14" /> Entrando…
            } @else {
              Entrar
            }
          </button>
        </form>
      </div>
      <p class="footnote">Acesso restrito. Sem senha configurada, ninguém entra.</p>
    </main>
  `,
  styles: `
    :host {
      position: relative;
      display: block;
      min-height: 100dvh;
      background:
        radial-gradient(60rem 30rem at 50% -10%, var(--accent-soft), transparent 70%),
        var(--bg);
    }

    .theme {
      position: absolute;
      top: var(--space-4);
      right: var(--space-4);
    }

    .wrap {
      display: flex;
      flex-direction: column;
      align-items: center;
      justify-content: center;
      gap: var(--space-5);
      min-height: 100dvh;
      padding: var(--space-6) var(--space-4);
    }

    .card {
      width: min(400px, 100%);
      padding: var(--space-8);
      border: 1px solid var(--border);
      border-radius: var(--radius-lg);
      background: var(--surface);
      box-shadow: var(--shadow-lg);
    }

    .brand {
      display: flex;
      flex-direction: column;
      align-items: center;
      gap: var(--space-2);
      margin-bottom: var(--space-6);
      text-align: center;
    }

    .logo {
      display: grid;
      place-items: center;
      width: 44px;
      height: 44px;
      margin-bottom: var(--space-2);
      border-radius: var(--radius-md);
      background: var(--accent);
      color: var(--accent-contrast);
    }

    h1 {
      font-size: var(--text-xl);
    }

    .brand p {
      font-size: var(--text-sm);
      color: var(--text-muted);
    }

    code {
      font-family: var(--font-mono);
      font-size: 0.92em;
    }

    .notice {
      display: flex;
      align-items: center;
      gap: var(--space-2);
      margin-bottom: var(--space-5);
      padding: var(--space-3);
      border-radius: var(--radius-sm);
      font-size: var(--text-sm);
    }

    .notice.tone-info {
      background: var(--info-soft);
      color: var(--info);
    }

    .notice.tone-warning {
      background: var(--warning-soft);
      color: var(--warning);
    }

    form {
      display: flex;
      flex-direction: column;
      gap: var(--space-4);
    }

    .password {
      position: relative;
    }

    .password .input {
      padding-right: 44px;
    }

    .reveal {
      position: absolute;
      top: 4px;
      right: 4px;
    }

    .error {
      font-size: var(--text-sm);
    }

    .footnote {
      font-size: var(--text-xs);
      color: var(--text-subtle);
    }

    @media (max-width: 480px) {
      .card {
        padding: var(--space-6) var(--space-5);
      }
    }
  `,
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class LoginPage {
  /** Query parameters (withComponentInputBinding) */
  readonly returnUrl = input<string>();
  readonly reason = input<string>();

  protected readonly theme = inject(ThemeService);
  private readonly auth = inject(AuthService);
  private readonly router = inject(Router);

  protected readonly username = signal('');
  protected readonly password = signal('');
  protected readonly showPassword = signal(false);
  protected readonly submitting = signal(false);
  protected readonly error = signal<'credentials' | 'server' | null>(null);

  protected readonly notice = computed(() => (this.error() ? null : NOTICES[this.reason() ?? ''] ?? null));
  protected readonly canSubmit = computed(
    () => !this.submitting() && this.username().trim() !== '' && this.password() !== '',
  );
  protected readonly errorText = computed(() => {
    switch (this.error()) {
      case 'credentials':
        return 'Usuário ou senha incorretos.';
      case 'server':
        return 'O servidor não respondeu. Tente de novo em instantes.';
      default:
        return '';
    }
  });

  protected submit(): void {
    if (!this.canSubmit()) {
      return;
    }
    this.submitting.set(true);
    this.error.set(null);
    this.auth.login(this.username().trim(), this.password()).subscribe({
      next: (session) => {
        if (!session) {
          this.submitting.set(false);
          this.error.set('server');
          return;
        }
        void this.router.navigateByUrl(this.safeReturnUrl());
      },
      error: (error: unknown) => {
        this.submitting.set(false);
        this.password.set('');
        this.error.set(error instanceof HttpErrorResponse && error.status === 401 ? 'credentials' : 'server');
      },
    });
  }

  /** Only paths inside the app: never an absolute URL handed over in the query string. */
  private safeReturnUrl(): string {
    const target = this.returnUrl();
    return target && target.startsWith('/') && !target.startsWith('//') && !target.startsWith('/login')
      ? target
      : '/';
  }
}

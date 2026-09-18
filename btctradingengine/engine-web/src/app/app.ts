import { ChangeDetectionStrategy, Component, inject } from '@angular/core';
import { RouterOutlet } from '@angular/router';
import { ThemeService } from './core/theme/theme.service';
import { ConfirmHostComponent } from './shared/ui/confirm';
import { ToastHostComponent } from './shared/ui/toast';

@Component({
  selector: 'bte-root',
  imports: [RouterOutlet, ToastHostComponent, ConfirmHostComponent],
  template: `
    <router-outlet />
    <bte-toast-host />
    <bte-confirm-host />
  `,
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class App {
  // Applies the saved or system theme before the first page renders
  protected readonly theme = inject(ThemeService);
}

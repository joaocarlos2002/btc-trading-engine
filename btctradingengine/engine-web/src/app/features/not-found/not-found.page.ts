import { ChangeDetectionStrategy, Component } from '@angular/core';
import { RouterLink } from '@angular/router';
import { EmptyStateComponent } from '../../shared/ui/feedback.components';

@Component({
  selector: 'bte-not-found-page',
  imports: [EmptyStateComponent, RouterLink],
  template: `
    <bte-empty-state
      icon="compass"
      title="Página não encontrada"
      description="O endereço não existe ou mudou. Volte para o painel ao vivo."
    >
      <a class="btn btn-primary" routerLink="/live">Ir para Ao vivo</a>
    </bte-empty-state>
  `,
  styles: `
    :host {
      display: grid;
      place-items: center;
      min-height: 60vh;
    }

    a {
      margin-top: var(--space-3);
      text-decoration: none;
    }
  `,
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class NotFoundPage {}

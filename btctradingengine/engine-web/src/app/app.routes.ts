import { Routes } from '@angular/router';
import { authGuard, guestGuard } from './core/auth/auth.guards';
import { ShellComponent } from './layout/shell.component';

export const routes: Routes = [
  {
    path: 'login',
    title: 'Entrar',
    canActivate: [guestGuard],
    loadComponent: () => import('./features/login/login.page').then((m) => m.LoginPage),
  },
  {
    path: '',
    component: ShellComponent,
    canActivate: [authGuard],
    children: [
      { path: '', pathMatch: 'full', redirectTo: 'live' },
      {
        path: 'live',
        title: 'Ao vivo',
        loadComponent: () => import('./features/live/live.page').then((m) => m.LivePage),
      },
      {
        path: 'backtest',
        title: 'Backtest',
        loadComponent: () => import('./features/backtest/backtest.page').then((m) => m.BacktestPage),
      },
      {
        path: '**',
        title: 'Página não encontrada',
        loadComponent: () => import('./features/not-found/not-found.page').then((m) => m.NotFoundPage),
      },
    ],
  },
];

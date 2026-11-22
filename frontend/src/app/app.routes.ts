import { Routes } from '@angular/router';
import { homeRedirect, requireRole } from './core/guards';
import { LoginComponent } from './login.component';

export const routes: Routes = [
  { path: 'login', component: LoginComponent, title: 'Sign in · Lodge' },
  { path: 'claims', canActivate: [requireRole('CLAIMANT')], title: 'My claims · Lodge',
    loadComponent: () => import('./claimant/my-claims.component').then(m => m.MyClaimsComponent) },
  { path: 'claims/new', canActivate: [requireRole('CLAIMANT')], title: 'Report an incident · Lodge',
    loadComponent: () => import('./claimant/submit-claim.component').then(m => m.SubmitClaimComponent) },
  { path: 'claims/:id', canActivate: [requireRole('CLAIMANT')], title: 'Claim · Lodge',
    loadComponent: () => import('./claimant/claim-detail.component').then(m => m.ClaimDetailComponent) },
  { path: 'queue', canActivate: [requireRole('OFFICER', 'MANAGER')], title: 'Claims queue · Lodge',
    loadComponent: () => import('./staff/queue.component').then(m => m.QueueComponent) },
  { path: 'workbench/:id', canActivate: [requireRole('OFFICER', 'MANAGER')], title: 'Workbench · Lodge',
    loadComponent: () => import('./staff/workbench.component').then(m => m.WorkbenchComponent) },
  { path: 'dashboard', canActivate: [requireRole('MANAGER')], title: 'Dashboard · Lodge',
    loadComponent: () => import('./manager/dashboard.component').then(m => m.DashboardComponent) },
  { path: '', pathMatch: 'full', canActivate: [homeRedirect], children: [] },
  { path: '**', redirectTo: '' },
];

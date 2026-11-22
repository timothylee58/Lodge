import { Routes } from '@angular/router';
import { homeRedirect, requireRole } from './core/guards';
import { LoginComponent } from './login.component';

export const routes: Routes = [
  { path: 'login', component: LoginComponent, title: 'Sign in · ClaimDesk' },
  { path: 'claims', canActivate: [requireRole('CLAIMANT')], title: 'My claims · ClaimDesk',
    loadComponent: () => import('./claimant/my-claims.component').then(m => m.MyClaimsComponent) },
  { path: 'claims/new', canActivate: [requireRole('CLAIMANT')], title: 'Report an incident · ClaimDesk',
    loadComponent: () => import('./claimant/submit-claim.component').then(m => m.SubmitClaimComponent) },
  { path: 'claims/:id', canActivate: [requireRole('CLAIMANT')], title: 'Claim · ClaimDesk',
    loadComponent: () => import('./claimant/claim-detail.component').then(m => m.ClaimDetailComponent) },
  { path: 'queue', canActivate: [requireRole('OFFICER', 'MANAGER')], title: 'Claims queue · ClaimDesk',
    loadComponent: () => import('./staff/queue.component').then(m => m.QueueComponent) },
  { path: 'workbench/:id', canActivate: [requireRole('OFFICER', 'MANAGER')], title: 'Workbench · ClaimDesk',
    loadComponent: () => import('./staff/workbench.component').then(m => m.WorkbenchComponent) },
  { path: 'dashboard', canActivate: [requireRole('MANAGER')], title: 'Dashboard · ClaimDesk',
    loadComponent: () => import('./manager/dashboard.component').then(m => m.DashboardComponent) },
  { path: '', pathMatch: 'full', canActivate: [homeRedirect], children: [] },
  { path: '**', redirectTo: '' },
];

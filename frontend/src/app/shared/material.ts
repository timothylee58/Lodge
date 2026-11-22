import { MatButtonModule } from '@angular/material/button';
import { MatButtonToggleModule } from '@angular/material/button-toggle';
import { MatCardModule } from '@angular/material/card';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatPaginatorModule } from '@angular/material/paginator';
import { MatProgressBarModule } from '@angular/material/progress-bar';
import { MatSelectModule } from '@angular/material/select';
import { MatTableModule } from '@angular/material/table';

/** The Material building blocks the screens use, in one import. */
export const MATERIAL = [MatButtonModule, MatButtonToggleModule, MatCardModule, MatFormFieldModule, MatInputModule,
  MatPaginatorModule, MatProgressBarModule, MatSelectModule, MatTableModule] as const;

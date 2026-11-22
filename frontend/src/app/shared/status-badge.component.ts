import { Component, computed, input } from '@angular/core';
import { ClaimStatus } from '../core/models';
import { STATUS_LABEL } from './format';

@Component({
  selector: 'cd-status',
  standalone: true,
  template: `<span class="badge" [attr.data-status]="status()">{{ label() }}</span>`,
})
export class StatusBadgeComponent {
  status = input.required<ClaimStatus>();
  label = computed(() => STATUS_LABEL[this.status()]);
}

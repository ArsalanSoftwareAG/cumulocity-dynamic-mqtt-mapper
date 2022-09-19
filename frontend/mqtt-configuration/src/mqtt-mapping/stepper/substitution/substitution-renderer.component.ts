import { Component, Input, OnInit, ViewChild, ViewEncapsulation } from '@angular/core';
import { Mapping } from '../../../shared/mqtt-configuration.model';


@Component({
  selector: 'mapping-substitution-renderer',
  templateUrl: 'substitution-renderer.component.html',
  styleUrls: ['./substitution-renderer.style.css',
  ],
  encapsulation: ViewEncapsulation.None,
})

export class SubstitutionRendererComponent implements OnInit {

  @Input()
  substitutions: Mapping[] = [];

  @Input()
  setting: {color : 'green', selectedSubstitutionIndex:1};

  constructor() { }

  ngOnInit() {
    console.log ("Setting for renderer:", this.setting)
  }
}
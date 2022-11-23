import { Component, EventEmitter, OnInit, ViewChild, ViewEncapsulation } from '@angular/core';
import { ActionControl, AlertService, BuiltInActionType, Column, ColumnDataType, DataGridComponent, DisplayOptions, gettext, Pagination, Row, WizardConfig, WizardService } from '@c8y/ngx-components';
import { v4 as uuidv4 } from 'uuid';
import { BrokerConfigurationService } from '../../mqtt-configuration/broker-configuration.service';
import { API, Mapping, MappingSubstitution, MappingType, Operation, PayloadWrapper, QOS, SnoopStatus } from '../../shared/mapping.model';
import { isTemplateTopicUnique, SAMPLE_TEMPLATES_C8Y } from '../../shared/util';
import { APIRendererComponent } from '../renderer/api.renderer.component';
import { QOSRendererComponent } from '../renderer/qos-cell.renderer.component';
import { StatusRendererComponent } from '../renderer/status-cell.renderer.component';
import { TemplateRendererComponent } from '../renderer/template.renderer.component';
import { MappingService } from '../core/mapping.service';
import { ModalOptions } from 'ngx-bootstrap/modal';
import { takeUntil } from 'rxjs/operators';
import { Subject } from 'rxjs';
import { StepperConfiguration } from '../stepper/stepper-model';

@Component({
  selector: 'mapping-mapping-grid',
  templateUrl: 'mapping.component.html',
  styleUrls: ['../shared/mapping.style.css'],
  encapsulation: ViewEncapsulation.None,
})

export class MappingComponent implements OnInit {

  isSubstitutionValid: boolean

  showConfigMapping: boolean = false;

  isConnectionToMQTTEstablished: boolean;

  mappings: Mapping[] = [];
  mappingToUpdate: Mapping;
  stepperConfiguration: StepperConfiguration = {
    showEditorSource: true,
    allowNoDefinedIdentifier: false,
    showProcessorExtensions: false,
    allowTesting: true
  };

  displayOptions: DisplayOptions = {
    bordered: true,
    striped: true,
    filter: false,
    gridHeader: true
  };

  columns: Column[] = [
    {
      name: 'id',
      header: '#',
      path: 'id',
      filterable: false,
      dataType: ColumnDataType.TextShort,
      visible: true
    },
    {
      header: 'Subscription Topic',
      name: 'subscriptionTopic',
      path: 'subscriptionTopic',
      filterable: true,
      gridTrackSize: '12.5%'
    },
    {
      header: 'Template Topic',
      name: 'templateTopic',
      path: 'templateTopic',
      filterable: true,
      gridTrackSize: '12.5%'
    },
    {
      name: 'targetAPI',
      header: 'API',
      path: 'targetAPI',
      filterable: false,
      sortable: false,
      dataType: ColumnDataType.TextShort,
      cellRendererComponent: APIRendererComponent,
      gridTrackSize: '5%'
    },
    {
      header: 'Sample payload',
      name: 'source',
      path: 'source',
      filterable: true,
      cellRendererComponent: TemplateRendererComponent,
      gridTrackSize: '22%'
    },
    {
      header: 'Target',
      name: 'target',
      path: 'target',
      filterable: true,
      cellRendererComponent: TemplateRendererComponent,
      gridTrackSize: '22%'
    },
    {
      header: 'Active-Tested-Snooping',
      name: 'active',
      path: 'active',
      filterable: false,
      sortable: false,
      cellRendererComponent: StatusRendererComponent,
      cellCSSClassName: 'text-align-center',
      gridTrackSize: '10%'
    },
    {
      header: 'QOS',
      name: 'qos',
      path: 'qos',
      filterable: true,
      sortable: false,
      cellRendererComponent: QOSRendererComponent,
      gridTrackSize: '5%'
    },
  ]

  value: string;
  mappingType: MappingType;
  destroy$: Subject<boolean> = new Subject<boolean>();
  refresh: EventEmitter<any> = new EventEmitter();

  pagination: Pagination = {
    pageSize: 3,
    currentPage: 1,
  };
  actionControls: ActionControl[] = [];

  constructor(
    public mappingService: MappingService,
    public configurationService: BrokerConfigurationService,
    public alertService: AlertService,
    private wizardService: WizardService,
  ) { }

  ngOnInit() {
    this.loadMappings();
    this.actionControls.push(
      {
        type: BuiltInActionType.Edit,
        callback: this.editMapping.bind(this)
      },
      {
        text: 'Copy',
        type: 'COPY',
        icon: 'copy',
        callback: this.copyMapping.bind(this)
      },
      {
        type: BuiltInActionType.Delete,
        callback: this.deleteMapping.bind(this)
      });
  }

  onRowClick(mapping: Row) {
    console.log('Row clicked:');
    this.editMapping (mapping as Mapping);
  }

  onAddMapping() {
    const wizardConfig: WizardConfig = {
      headerText: 'Add Mapping',
      headerIcon: 'plus-circle',
      bodyHeaderText: 'Select mapping type',
    };
    const initialState = {
      id: 'addMappingWizard',
      wizardConfig,
    };

    const modalOptions: ModalOptions = { initialState } as any;
    const modalRef = this.wizardService.show(modalOptions);
    modalRef.content.onClose.pipe(takeUntil(this.destroy$)).subscribe(result => {
      console.log("Was selected:", result);
      this.mappingType = result;
      if (result) {
        this.addMapping();
      }
    });
  }

  async addMapping() {
    this.stepperConfiguration = {
      showEditorSource: true,
      allowNoDefinedIdentifier: false,
      showProcessorExtensions: false,
      allowTesting: true,
      updateMode: false
    };

    let ident = uuidv4();
    let sub: MappingSubstitution[] = [];
    let mapping: Mapping = {
      name: "Mapping - " + ident.substring(0, 7),
      id: ident,
      ident: ident,
      subscriptionTopic: '',
      templateTopic: '',
      templateTopicSample: '',
      targetAPI: API.MEASUREMENT.name,
      source: '{}',
      target: SAMPLE_TEMPLATES_C8Y[API.MEASUREMENT.name],
      active: false,
      tested: false,
      qos: QOS.AT_LEAST_ONCE,
      substitutions: sub,
      mapDeviceIdentifier: false,
      createNonExistingDevice: false,
      mappingType: this.mappingType,
      updateExistingDevice: false,
      externalIdType: 'c8y_Serial',
      snoopStatus: SnoopStatus.NONE,
      snoopedTemplates: [],
      lastUpdate: Date.now()
    }
    if (this.mappingType == MappingType.FLAT_FILE) {
      let sampleSource = JSON.stringify({
        message: '10,temp,1666963367'
      } as PayloadWrapper);
      mapping = {
        ...mapping,
        source: sampleSource
      }
    } else if (this.mappingType == MappingType.PROCESSOR_EXTENSION) {
      mapping.extension = {
        event: undefined,
        name: undefined,
        message:undefined
      }
    }
    this.setStepperConfiguration(this.mappingType)

    this.mappingToUpdate = mapping;
    console.log("Add mappping", this.mappings)
    this.refresh.emit();
    this.showConfigMapping = true;
  }

  editMapping(mapping: Mapping) {
    this.stepperConfiguration = {
      showEditorSource: true,
      allowNoDefinedIdentifier: false,
      showProcessorExtensions: false,
      allowTesting: true,
      updateMode: true
    };
    this.setStepperConfiguration(mapping.mappingType);
    // create deep copy of existing mapping, in case user cancels changes
    this.mappingToUpdate = JSON.parse(JSON.stringify(mapping));
    console.log("Editing mapping", this.mappingToUpdate);
    this.showConfigMapping = true;
  }

  copyMapping(mapping: Mapping) {
    this.stepperConfiguration = {
      showEditorSource: true,
      allowNoDefinedIdentifier: false,
      showProcessorExtensions: false,
      allowTesting: true,
      updateMode: false
    };
    this.setStepperConfiguration(mapping.mappingType)
    // create deep copy of existing mapping, in case user cancels changes
    this.mappingToUpdate = JSON.parse(JSON.stringify(mapping)) as Mapping;
    this.mappingToUpdate.name =  this.mappingToUpdate.name + " - Copy";
    this.mappingToUpdate.ident = uuidv4();
    this.mappingToUpdate.id = this.mappingToUpdate.ident
    console.log("Copying mapping", this.mappingToUpdate);
    this.showConfigMapping = true;
  }

  async deleteMapping(mapping: Mapping) {
    console.log("Deleting mapping:", mapping)
    await this.mappingService.deleteMapping(mapping);
    this.alertService.success(gettext('Mapping deleted successfully'));
    this.isConnectionToMQTTEstablished = true;
    this.loadMappings();
    this.refresh.emit();
    this.activateMappings();
  }


  async loadMappings(): Promise<void> {
    this.mappings = await this.mappingService.loadMappings();
    console.log("Updated mappings", this.mappings);
  }

  async onCommit(mapping: Mapping) {
    // test if new/updated mapping was commited or if cancel
    mapping.lastUpdate = Date.now();

    console.log("Changed mapping:", mapping);

    if (isTemplateTopicUnique(mapping, this.mappings)) {
      if (this.stepperConfiguration.updateMode) {
        console.log("Update existing mapping:", mapping);
        await this.mappingService.updateMapping(mapping);
        this.alertService.success(gettext('Mapping updated successfully'));
      } else {
        // new mapping
        console.log("Push new mapping:", mapping);
        await this.mappingService.createMapping(mapping);
        this.alertService.success(gettext('Mapping created successfully'));
      }
      this.loadMappings();
      this.refresh.emit();
      this.isConnectionToMQTTEstablished = true;
      this.activateMappings();
    } else {
      this.alertService.danger(gettext('Topic is already used: ' + mapping.subscriptionTopic + ". Please use a different topic."));
    }
    this.showConfigMapping = false;
  }

  async onSaveClicked() {
    this.saveMappings();
  }

  async onActivateClicked() {
    this.activateMappings();
  }

  private async activateMappings() {
    const response2 = await this.configurationService.runOperation(Operation.RELOAD_MAPPINGS);
    console.log("Activate mapping response:", response2)
    if (response2.status < 300) {
      this.alertService.success(gettext('Mappings activated successfully'));
      this.isConnectionToMQTTEstablished = true;
    } else {
      this.alertService.danger(gettext('Failed to activate mappings'));
    }
  }

  private async saveMappings() {
    await this.mappingService.saveMappings(this.mappings);
    console.log("Saved mppings:", this.mappings)
    this.alertService.success(gettext('Mappings saved successfully'));
    this.isConnectionToMQTTEstablished = true;
    // if (response1.res.ok) {
    // } else {
    //   this.alertService.danger(gettext('Failed to save mappings'));
    // }
  }

  setStepperConfiguration(mappingType: MappingType) {
    if (mappingType == MappingType.PROTOBUF_STATIC) {
      this.stepperConfiguration = {
        ...this.stepperConfiguration,
        showProcessorExtensions: false,
        showEditorSource: false,
        allowNoDefinedIdentifier: true,
        allowTesting: false
      }
    } else if (mappingType == MappingType.PROCESSOR_EXTENSION) {
      this.stepperConfiguration = {
        ...this.stepperConfiguration,
        showProcessorExtensions: true,
        showEditorSource: false,
        allowNoDefinedIdentifier: true,
        allowTesting: false
      }
    }
  }

  ngOnDestroy() {
    this.destroy$.next(true);
    this.destroy$.unsubscribe();
  }

}


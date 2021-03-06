import {Component, OnInit, Inject, ElementRef} from 'angular2/core';
import {RouteParams} from 'angular2/router';

import {SensorService} from '../sensor.service';
import {NavService} from '../nav.service';
import {JobService} from './job.service';

import {Sensor} from '../sensor';
import {SensorFactory} from '../sensor.factory';
import {Job} from './job';
import {SensorComponent} from '../sensor.component';

@Component({
    templateUrl: 'app/technician/configure-job.component.html',
    directives: [SensorComponent]

})
export class ConfigureJobComponent implements OnInit {
    private job: Job;    
    chart: any;
	status: string;
    statusPercentage: number;
    private modalElement: any;

	// List of devices
	sensors: Sensor[];

	constructor(
        private _sensorService: SensorService,
        private _jobService: JobService,
        private _navService: NavService,
        private _routeParams: RouteParams,
        private _elementRef: ElementRef,
        @Inject('jQuery') private _jquery,
        @Inject('Foundation') private _foundation,
        private _sensorFactory: SensorFactory
	) { }

	ngOnInit() {
        this.modalElement = this._jquery(this._elementRef.nativeElement.children[0]);
        var elem = new this._foundation.Reveal(this.modalElement, { closeOnClick: false });

        var policyNumber = this._routeParams.get('policyNumber');

        this.job = this._jobService.getJob(policyNumber);
        if (this.job === null) {
            window.history.back();
        }
        this._navService.setTitle(this.job.name);

        this.statusPercentage = 0;

        this.sensors = [];
        var savedSensors = this._sensorService.getSensorsForPolicy(policyNumber);
        if (savedSensors.length > 0 && savedSensors[0].status === "DISCONNECTED") {
            for (let savedSensor of savedSensors) {
                var sensor = this._sensorFactory.sensor(this.job.policyNumber);
                sensor.setName(savedSensor.name);

                sensor.setSystemId(savedSensor.systemId);
                this.sensors.push(sensor);
            }
        } else {
            this.sensors = savedSensors;
        }
    }

    connectToNearestDevice() {
        var self = this;

        var sensor = this._sensorFactory.sensor(this.job.policyNumber);

        sensor.connectToNearestDevice((sensor, status) => {
            self.statusHandler(sensor, status);
        })
    }

    statusHandler(sensor, status) {
        if ('SCANNING' == status) {
            this.modalElement.foundation('open');
            this.statusPercentage = 20
        } else if ('SENSORTAG_FOUND' == status) {
            this.statusPercentage = 40
        } else if ('CONNECTING' == status) {
            this.statusPercentage = 60
        } else if ('READING_DEVICE_INFO' == status) {
            this.statusPercentage = 80
        } else if ('DEVICE_INFO_AVAILABLE' == status) {
            this.statusPercentage = 100;
            this.deviceConnectedHandler(sensor);
        } else if ('SENSORTAG_NOT_FOUND' == status) {
            this.statusPercentage = 0;
        }

        this.status = status;
    }

    deviceConnectedHandler(sensor) {
        this._sensorService.addSensor(sensor);
        this.sensors = this._sensorService.getSensorsForPolicy(this.job.policyNumber);
    }

    saveDevices() {
        this._sensorService.sync();
        window.history.back();
    }

    nameSensor(sensorName) {
        this.modalElement.foundation('close');
        this.sensors[this.sensors.length - 1].name = "Sensor "+(this.sensors.length);
        this.status = ""
    }

    // Handle device disconnected
    deviceDisconnectedHandler(sensor) {
        this._sensorService.removeSensor(sensor.systemId);
        this.sensors = this._sensorService.getSensorsForPolicy(this.job.policyNumber);
    }

}
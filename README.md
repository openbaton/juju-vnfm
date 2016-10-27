  <img src="https://raw.githubusercontent.com/openbaton/openbaton.github.io/master/images/openBaton.png" width="250"/>
  
  Copyright © 2015-2016 [Open Baton](http://openbaton.org). Licensed under [Apache v2 License](http://www.apache.org/licenses/LICENSE-2.0).


# Juju VNF Manager (Beta version)
The Juju VNF Manager enables the Open Baton NFVO to interoperat with Juju as a generic VNFM. This initial version does not provide full interoperability between this VNFM and the Generic one provided by Open Baton. However, with this VNFM you can execute the following: 

* deploy Juju charms which are available on the [juju-store]
* deploy Open Baton VNF Packages 

Please be careful in reading the list of unsupported functionalities in the next section. Those functionalities are part of the roadmap of this component and will be available in the next releases.  

## Constraints and future work
Here is a list of known constraints and features that do not work at the moment. 

In case you are planning to use the Juju VNFM to deploy Open Baton VNF Packages keep in mind that: 

* Lifecycle scripts have to be bash scripts
* Execution order of CONFIGURE lifecycle scripts cannot be ensured
* No actual creation of networks (virtual links)
* VNFDs with the same name in different NSDs cause problems

In case you are planning to use the Juju VNFM to deploy Juju charms then keep in mind that: 

* Dependencies between charms from the Juju Charm Store cannot be resolved yet

In general, keep in mind that: 

* No accurate NSR/VNFR status feedback
* Scaling is not supported
* Dependencies between VNFs are only working when using the same VNFM

These list of issues is something we are working on for the future release.

## Requirements

* A running NFVO (> v2.1.3)
* A running Juju instance with a controller named *obcontroller*. Please refer to the juju [installation guide][installation-guide]
* The Juju-VNFM needs to run on the same machine where Juju is installed. Please make sure that you have also a JDK installed in order to compile this source code. 

Juju has to contain a controller named *obcontroller*. You can bootsrap a controller by executing (you can see more details on the juju [installation guide][installation-juju-create-container]

```bash
juju bootstrap obcontroller {cloudname}
```

The cloudname specifies where the controller will run and instantiate the model (e.g. lxd, openstack). 
This step is fairly important and may differ depending on which cloud you want to use. 
Take a look at the tutorial sections down below to see an example for Openstack. 


## How to install the Juju VNF Manager from source code

Git clone the project into the /opt/openbaton directory (you may need to be logged in as root user, unless you change the permissions on the /opt/openbaton folder): 

```bash
sudo apt-get install openjdk-7-jdk #in case you don't have java installed
mkdir /opt/openbaton
git clone https://github.com/openbaton/juju-vnfm.git
```

And Execute 

```bash
cd /opt/openbaton/juju-vnfm; ./juju-vnfm.sh compile
```
to compile it. 

## Configure the Juju VNF Manager

The Juju VNF Manager uses AMQP to communicate with the NFVO, therefore it needs to reach RabbitMQ in order to properly register with the NFVO.  
In order to configure the VNFM to reach the proper NFVO you need to modify the juju configuration file. First of all, copy the application.properties file into the following location

```bash
sudo mkdir /etc/openbaton/ # in case it does not exists
sudo cp src/main/resources/application.properties /etc/openbaton/
```

Then change open it with your favourite editor and modify the properties *spring.rabbitmq.host* and *spring.rabbitmq.port* adding the ip address and port on which rabbitmq is running. Please make sure to update also the *spring.rabbitmq.username* and *spring.rabbitmq.password* with the one used also on the NFVO side. 

## How to control the Juju VNF Manager

To start the Juju VNF Manager execute

 ```bash
 cd /opt/openbaton/juju-vnfm
 ./juju-vnfm.sh start
 ```

This will create a new screen window which you can access using *screen -x openbaton*. 

## How to use the Juju VNF Manager


To use the Juju VNF Manager for deploying a network service you have to store a VimInstance with type *test* in the NFVO 
and the Virtual Network Function Descriptors used to describe the network service have to define their *endpoint* as *juju*. 
Now you can launch the NSD as usual. Juju uses Ubuntu series names to specify which image to use while deploying a charm.
You can specify this series in the application.properties, the default is trusty. 

If you want to deploy a charm from the Juju Charm Store you have to set the VNFD's *vnfPackageLocation* to *juju charm store* 
and name the VNFD after the charm name. 
This will cause the Juju VNFM to deploy the specified charm from the Juju Charm Store. 
This is currently a fairly simple mechanism and does not provide further integration into the Open Baton Network Service deployment. 
So it is not possible to include a VNFD that specifies a Juju Charm that has dependencies to other VNFDs or to pass configurations while deploying the Charm. 

 
## How it works

The Juju VNFM translates Open Baton NSD's into Juju Charms, stores them in directories in */tmp/openbaton/juju* and deploys them 
using an already running Juju controller.  
Therefore it has an internal NetworkService class which will store information about the NSD, VNFDs, VNFRs and dependencies that it 
gets from the Open Baton NFVO. After the NFVO transmitted the last START event of a Network Service to the Juju VNFM, the charm is 
created and the juju deploy command called. If dependencies exist, the Juju VNFM will also add relations between the charms. 
The charm directory will be removed afterwards. 
In the following diagram you can see the work flow. 

![Juju flow][juju-flow]

The basic lifecycle mapping between the Open Baton and the Juju model maps Open Baton's INSTANTIATE lifecycle to Juju's install hook, the START lifecycle 
to the start hook and the TERMINATE lifecycle to the stop hook. 
But since Open Baton and Juju handle dependencies differently you cannot simply map Open Baton's CONFIGURE lifecycle to an existing Juju hook. 
The following table shows in which VNFD dependency cases which Open Baton lifecycle is mapped to which Juju hook. 
That means the scripts used by this lifecycle event will be executed by the corresponding Juju hook. 

![Mapping table][mapping-table]

If the VNFD is a target of a dependency the START lifecycle will not be mapped to the start hook because the lifecycle's scripts 
should be executed after the relation-changed hook which runs the CONFIGURE scripts. 
In these cases a *startAfterDependency* script will be created and the relation-changed hook will trigger its execution after it has finished. 


## OpenIMS Tutorial with Juju-VNFM and Openstack

This Tutorial will demonstrate how to deploy the OpenIMSCore implementation on Openstack using Open Baton and the Juju-VNFM. 

### Requirements

* NFVO
* Juju
* Juju-VNFM
* Openstack
* [OpenIMSCore packages][openimscore-github]

### Configure Juju to work with Openstack

Create a document called *mystack.yaml*. 
Here you will provide the necessary information for Juju to connect to Openstack. 
The content of the file could look something like this:

``` yaml
  clouds:
    mystack:
      type: openstack
      auth-types: [userpass]
      use-floating-ip: true
      use-default-secgroup: true
      regions:
        RegionOne:
          endpoint: http://auth-url:5000/v2.0
```

Values for *auth-type*, *region* or *endpoint* may vary according to your Openstack setup, but *use-floating-ip* and *use-default-secgroup* 
have to be set to true. 

Now we will add the cloud to Juju with the following command: 

``` bash
juju add-cloud mystack {pathToMystackFile}/mystack.yaml
```

The next step is to set credentials for the mystack cloud configuration. Execute the following command and enter the required values. 

``` yaml
juju add-credential mystack
```

### Configure Openstack to work with Juju

Then we also have to configure Openstack to provide an image usable by Juju. 
For this please refer to [this][juju-private-cloud] guide. 

### Bootstrap the environment

Finally we can bootstrap the openstack environment using this command:

``` bash
juju bootstrap obcontroller mystack --config image-metadata-url=$METADATA_URL --config network=$NETWORK_ID --config use-floating-ip=true --config use-default-secgroup=true --bootstrap-series=$SERIES
```

where the $METADATA_URL has to be replaced by the endpoint you created in the previously mentioned guide to configure Openstack. 
If you followed it you should be able to obtain the url by executing 

``` bash
openstack endpoint show product-streams
```

and copying the printed public url. 
It will look something like this: 
http:/\/openstack-url:8080/v1/AUTH_e4f353a5d2184b1fa3f359aaf02abbee/simplestreams/images

The $NETWORK_ID has to be the network's id from Openstack that shall be used. 

And the $SERIES should be set to an Ubuntu series according to the image you want to use. 
By default the Juju-VNFM uses trusty while deploying. 

### Deploy the OpenIMSCore

Now that the setup is ready we can start looking at the actual deployment of the OpenIMSCore. 

Clone the git repository containing the packages from [here][openimscore-github]. 
But before we create tar archives for uploading to the NFVO we have to configure them to work with the Juju-VNFM. 
In every component's directory (bind9, fhoss, scscf, pcscf, icscf) you will find a file containing the VNFD. 
In these VNFDs you have to change the *endpoint* value from generic to juju. 
Furthermore you (currently) have to edit a file in the script folders of icscf, pccs and scscf. 
That are the files *icscf.conf*, *scscf.conf* and *pcscf.conf*. 
In those scripts please comment the lines starting from **pre-start script** until the next **end script**. 

Like this for example: 

``` bash
# pre-start script
# 	exec /opt/OpenIMSCore/bin/icscf.kill.sh
# 	exit 0
# end script
```

Afterwards you can create the packages for each IMS component. 
For icscf for example change into the icscf directory and execute 

``` bash
tar -cf icscf.tar *
```

Start the NFVO and the Juju-VNFM and upload a VimInstance named *vim-instance* with type *test* to the NFVO. 
Then upload the VNFPackages to the NFVO using the Gui or Cli. 
This will also create VNFDs in the NFVO. Download this [NSD][openims-nsd] and replace the VNFD ids with the ones of the stored VNFDs. 
Upload the NSD to the NFVO and launch it. This will create an NSR and deploy the OpenIMSCore on Openstack. 
Since the correct reporting of the deployment's status from the Juju-VNFM to the NFVO is still a future task 
you should not rely on the NSR status shown by the Gui, but check using the *juju status* command. 


## Issue tracker

Issues and bug reports should be posted to the GitHub Issue Tracker of this project

## What is Open Baton?

Open Baton is an open source project providing a comprehensive implementation of the ETSI Management and Orchestration (MANO) specification and the TOSCA Standard.

Open Baton provides multiple mechanisms for interoperating with different VNFM vendor solutions. It has a modular architecture which can be easily extended for supporting additional use cases. 

It integrates with OpenStack as standard de-facto VIM implementation, and provides a driver mechanism for supporting additional VIM types. It supports Network Service management either using the provided Generic VNFM and Juju VNFM, or integrating additional specific VNFMs. It provides several mechanisms (REST or PUB/SUB) for interoperating with external VNFMs. 

It can be combined with additional components (Monitoring, Fault Management, Autoscaling, and Network Slicing Engine) for building a unique MANO comprehensive solution.

## Source Code and documentation

The Source Code of the other Open Baton projects can be found [here][openbaton-github] and the documentation can be found [here][openbaton-doc]

## News and Website

Check the [Open Baton Website][openbaton]

Follow us on Twitter @[openbaton][openbaton-twitter]

## Licensing and distribution
Copyright © [2015-2016] Open Baton project

Licensed under the Apache License, Version 2.0 (the "License");

you may not use this file except in compliance with the License.
You may obtain a copy of the License at

  http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.

## Support
The Open Baton project provides community support through the Open Baton Public Mailing List and through StackOverflow using the tags openbaton.

## Supported by
  <img src="https://raw.githubusercontent.com/openbaton/openbaton.github.io/master/images/fokus.png" width="250"/><img src="https://raw.githubusercontent.com/openbaton/openbaton.github.io/master/images/tu.png" width="150"/>

[fokus-logo]: https://raw.githubusercontent.com/openbaton/openbaton.github.io/master/images/fokus.png
[installation-guide]: https://jujucharms.com/docs/stable/getting-started
[installation-juju-create-container]: https://jujucharms.com/docs/stable/getting-started#create-a-controller
[openbaton]: http://openbaton.org
[openbaton-doc]: http://openbaton.org/documentation
[openbaton-github]: http://github.org/openbaton
[openbaton-logo]: https://raw.githubusercontent.com/openbaton/openbaton.github.io/master/images/openBaton.png
[openbaton-mail]: mailto:users@openbaton.org
[openbaton-twitter]: https://twitter.com/openbaton
[tub-logo]: https://raw.githubusercontent.com/openbaton/openbaton.github.io/master/images/tu.png
[juju-flow]: img/juju-flow.png
[juju-store]:https://jujucharms.com/store
[mapping-table]: img/mapping-table.png
[openimscore-github]: https://github.com/openbaton/openimscore-packages/tree/master
[juju-private-cloud]: https://jujucharms.com/docs/devel/howto-privatecloud
[openims-nsd]: http://openbaton.github.io/documentation/descriptors/tutorial-ims-NSR/tutorial-ims-NSR.json

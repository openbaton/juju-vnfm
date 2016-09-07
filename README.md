  <img src="https://raw.githubusercontent.com/openbaton/openbaton.github.io/master/images/openBaton.png" width="250"/>
  
  Copyright © 2015-2016 [Open Baton](http://openbaton.org). 
  Licensed under [Apache v2 License](http://www.apache.org/licenses/LICENSE-2.0).


# Juju VNF Manager (Beta version)
The Juju VNF Manager enables the Open Baton NFVO to interoperat with Juju as a generic VNFM. This initial version does not provide full interoperability between this VNFM and the Generic one provided by Open Baton. However, with this VNFM you can execute the following: 

* deploy Juju charms which are available on the [juju-store]
* deploy Open Baton VNF Packages 

Please be careful in reading the list of unsupported functionalities in the next section. Those functionalities are part of the roadmap of this component and will be available in the next releases.  

# Constraints and future work
Here is a list of known constraints and features that do not work at the moment. 

In case you are planning to use the Juju VNFM to deploy Open Baton VNF Packages keep in mind that: 

* Lifecycle scripts have to be bash scripts
* Lifecycle scripts should not depend on other files
* Execution order of CONFIGURE lifecycle scripts cannot be ensured
* No actual creation of networks (virtual links)
* VNFDs with the same name in different NSDs cause problems

In case you are planning to use the Juju VNFM to deploy Juju charms then keep in mind that: 

* Dependencies between charms from the Juju Charm Store cannot be resolved yet

In general, keep in mind that: 

* No accurate NSR/VNFR status feedback
* Scaling is not supported
* Dependencies between VNFs is working only when using the same VNFM
* Bidirectional dependencies cause problems

These list of issues is something we are working on for the future release.

# Requirements

* A running NFVO (> v2.1.3)
* A running Juju instance with a controller named *obcontroller*. Please refer to the juju [installation guide](installation-guide)
* The Juju-VNFM needs to run on the same machine where Juju is installed


# How to install the Juju VNF Manager from source code

Git clone the project into the /opt/openbaton directory (you may need to be logged in as root user, unless you change the permissions on the /opt/openbaton folder): 

```bash
mkdir /opt/openbaton
git clone https://github.com/openbaton/juju-vnfm.git
```

And Execute 

```bash
cd /opt/openbaton/juju-vnfm; ./juju-vnfm.sh compile
```
to compile it. 

# Configure the Juju VNF Manager

The Juju VNF Manager uses rabbitmq to communicate with the NFVO. 
If you want to run the Juju VNF Manager on another machine than on which rabbitmq is running you first have to configure it.  
Either you use the *application.properties* file in the project's resources folder to configure it or you create the file 
*/etc/openbaton/juju-vnfm.properties*, copy the previously mentioned *application.properties* file's content into it 
and configure it there.  
Then change the properties *spring.rabbitmq.host* and *spring.rabbitmq.port* to the ip address and host on which rabbitmq are running and compile again.  
If you decided to create the file */etc/openbaton/juju-vnfm.properties* the Juju VNF Manager will only use this one so make sure 
that all the properties from the file *application.properties* are present.  


# How to control the Juju VNF Manager

To start the Juju VNF Manager execute

 ```bash
 cd /opt/openbaton/juju-vnfm
 ./juju-vnfm.sh start
 ```

This will create a new screen window which you can access using *screen -x openbaton*.  
You have to run the Juju VNFM on the same machine on which Juju runs. 
Furthermore Juju has to contain a controller named *obcontroller*. 
You can bootsrap a controller by executing (you can see more details on the juju [installation guide](installation-juju-create-container))

```bash
juju bootstrap obcontroller {cloudname}
```

The cloudname specifies where the controller will run and instantiate the model (e.g. lxd, openstack). 

# How to use the Juju VNF Manager


To use the Juju VNF Manager for deploying a network service you have to store a VimInstance with type *test* in the NFVO 
and the Virtual Network Function Descriptors used to describe the network service have to define their *endpoint* as *juju*. 
Now you can launch the NSD as usual. 

If you want to deploy a charm from the Juju Charm Store you have to set the VNFD's *vnfPackageLocation* to *juju charm store* 
and name the VNFD after the charm name. 
This will cause the Juju VNFM to deploy the specified charm from the Juju Charm Store. 
This is currently a fairly simple mechanism and does not provide further integration into the Open Baton Network Service deployment. 
So it is not possible to include a VNFD that specifies a Juju Charm that has dependencies to other VNFDs or to pass configurations while deploying the Charm. 

 
# How it works

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


# Issue tracker

Issues and bug reports should be posted to the GitHub Issue Tracker of this project

# What is Open Baton?

OpenBaton is an open source project providing a comprehensive implementation of the ETSI Management and Orchestration (MANO) specification.

Open Baton is a ETSI NFV MANO compliant framework. Open Baton was part of the OpenSDNCore (www.opensdncore.org) project started almost three years ago by Fraunhofer FOKUS with the objective of providing a compliant implementation of the ETSI NFV specification. 

Open Baton is easily extensible. It integrates with OpenStack, and provides a plugin mechanism for supporting additional VIM types. It supports Network Service management either using a generic VNFM or interoperating with VNF-specific VNFM. It uses different mechanisms (REST or PUB/SUB) for interoperating with the VNFMs. It integrates with additional components for the runtime management of a Network Service. For instance, it provides autoscaling and fault management based on monitoring information coming from the monitoring system available at the NFVI level.

# Source Code and documentation

The Source Code of the other Open Baton projects can be found [here][openbaton-github] and the documentation can be found [here][openbaton-doc] .

# News and Website

Check the [Open Baton Website][openbaton]
Follow us on Twitter @[openbaton][openbaton-twitter].

# Licensing and distribution
Copyright [2015-2016] Open Baton project

Licensed under the Apache License, Version 2.0 (the "License");

you may not use this file except in compliance with the License.
You may obtain a copy of the License at

  http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.

# Support
The Open Baton project provides community support through the Open Baton Public Mailing List and through StackOverflow using the tags openbaton.

# Supported by
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
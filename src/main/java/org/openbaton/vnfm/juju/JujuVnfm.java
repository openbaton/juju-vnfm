/*
 * Copyright (c) 2016 Open Baton (http://www.openbaton.org)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.openbaton.vnfm.juju;

import org.openbaton.catalogue.mano.common.Event;
import org.openbaton.catalogue.mano.common.LifecycleEvent;
import org.openbaton.catalogue.mano.descriptor.VNFComponent;
import org.openbaton.catalogue.mano.descriptor.VNFDConnectionPoint;
import org.openbaton.catalogue.mano.descriptor.VirtualDeploymentUnit;
import org.openbaton.catalogue.mano.descriptor.VirtualNetworkFunctionDescriptor;
import org.openbaton.catalogue.mano.record.VNFCInstance;
import org.openbaton.catalogue.mano.record.VNFRecordDependency;
import org.openbaton.catalogue.mano.record.VirtualNetworkFunctionRecord;
import org.openbaton.catalogue.nfvo.*;
import org.openbaton.catalogue.nfvo.messages.Interfaces.NFVMessage;
import org.openbaton.catalogue.nfvo.messages.OrVnfmGenericMessage;
import org.openbaton.catalogue.nfvo.messages.OrVnfmInstantiateMessage;
import org.openbaton.catalogue.nfvo.messages.OrVnfmStartStopMessage;
import org.openbaton.common.vnfm_sdk.amqp.AbstractVnfmSpringAmqp;
import org.openbaton.common.vnfm_sdk.exception.BadFormatException;
import org.openbaton.common.vnfm_sdk.exception.NotFoundException;
import org.openbaton.common.vnfm_sdk.exception.VnfmSdkException;
import org.openbaton.common.vnfm_sdk.utils.VnfmUtils;
import org.openbaton.vnfm.juju.utils.InterfaceMap;
import org.openbaton.vnfm.juju.utils.NetworkService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.SpringApplication;
import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.Yaml;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermission;
import java.util.*;

/**
 * Created by tbr on 24.08.16.
 */
public class JujuVnfm extends AbstractVnfmSpringAmqp {

  private Map<String, NetworkService> networkServiceMap;

  private Set<PosixFilePermission> permissions;

  @Value("${vnfm.script-path:/opt/openbaton/scripts}")
  private String scriptPath;

  @Value("${vnfm.series:trusty}")
  private String series;

  public String getSeries() {
    return series;
  }

  public void setSeries(String series) {
    this.series = series;
  }

  public String getScriptLogPath() {
    return scriptLogPath;
  }

  public void setScriptLogPath(String scriptLogPath) {
    this.scriptLogPath = scriptLogPath;
  }

  @Value("${vnfm.script.logfile:/var/log/openbaton/scriptsLog}")
  private String scriptLogPath;

  public JujuVnfm() {
    super();
    networkServiceMap = new HashMap<>();
    permissions = new HashSet<>();
    permissions.add(PosixFilePermission.GROUP_EXECUTE);
    permissions.add(PosixFilePermission.OTHERS_EXECUTE);
    permissions.add(PosixFilePermission.OWNER_EXECUTE);
    permissions.add(PosixFilePermission.OTHERS_READ);
    permissions.add(PosixFilePermission.GROUP_READ);
    permissions.add(PosixFilePermission.OWNER_READ);
    permissions.add(PosixFilePermission.OTHERS_WRITE);
    permissions.add(PosixFilePermission.GROUP_WRITE);
    permissions.add(PosixFilePermission.OWNER_WRITE);
  }

  @Override
  protected synchronized NFVMessage onAction(NFVMessage message)
      throws NotFoundException, BadFormatException {

    VirtualNetworkFunctionRecord virtualNetworkFunctionRecord = null;
    NFVMessage nfvMessage = null;
    OrVnfmGenericMessage orVnfmGenericMessage = null;
    OrVnfmStartStopMessage orVnfmStartStopMessage = null;
    NetworkService networkService;

    try {
      switch (message.getAction()) {
        case INSTANTIATE:
          log.info(
              "Received NFVO Message: "
                  + message.getAction()
                  + " for VNFD "
                  + ((OrVnfmInstantiateMessage) message).getVnfd().getName());
          OrVnfmInstantiateMessage orVnfmInstantiateMessage = (OrVnfmInstantiateMessage) message;
          VirtualNetworkFunctionDescriptor vnfd = orVnfmInstantiateMessage.getVnfd();

          Map<String, Collection<VimInstance>> vimInstances =
              orVnfmInstantiateMessage.getVimInstances();
          virtualNetworkFunctionRecord =
              createVirtualNetworkFunctionRecord(
                  orVnfmInstantiateMessage.getVnfd(),
                  orVnfmInstantiateMessage.getVnfdf().getFlavour_key(),
                  orVnfmInstantiateMessage.getVlrs(),
                  orVnfmInstantiateMessage.getExtension(),
                  vimInstances);

          // fill networkServiceMap
          networkService = getNetworkService(orVnfmInstantiateMessage.getExtension().get("nsr-id"));

          // if the vnfd specifies a charm from the juju charm store add it to the charms list
          if (vnfd.getVnfPackageLocation().equals("juju charm store")) {
            log.info("Found a VNF from the juju charm store: " + vnfd.getName());
            networkService.addCharm(vnfd.getName());
          }

          // add vnfd and vnfpackage to the network service
          networkService.addVnfd(vnfd);
          networkService.addVnfPackage(orVnfmInstantiateMessage.getVnfPackage(), vnfd.getName());
          networkService.setVnfStatus(vnfd.getName(), "instantiated");
          //----

          if (orVnfmInstantiateMessage.getVnfPackage() != null) {
            if (orVnfmInstantiateMessage.getVnfPackage().getScriptsLink() != null)
              virtualNetworkFunctionRecord =
                  instantiate(
                      virtualNetworkFunctionRecord,
                      orVnfmInstantiateMessage.getVnfPackage().getScriptsLink(),
                      vimInstances);
            else
              virtualNetworkFunctionRecord =
                  instantiate(
                      virtualNetworkFunctionRecord,
                      orVnfmInstantiateMessage.getVnfPackage().getScripts(),
                      vimInstances);
          } else {
            virtualNetworkFunctionRecord =
                instantiate(virtualNetworkFunctionRecord, null, vimInstances);
          }
          nfvMessage = VnfmUtils.getNfvMessage(Action.INSTANTIATE, virtualNetworkFunctionRecord);
          log.info("After instantiate of " + vnfd.getName() + ":\n" + networkService.toString());
          break;
        case MODIFY:
          log.info(
              "Received NFVO Message: "
                  + message.getAction()
                  + " for VNFR "
                  + ((OrVnfmGenericMessage) message).getVnfr().getName()
                  + " and following vnfrDep: \n"
                  + ((OrVnfmGenericMessage) message).getVnfrd());
          orVnfmGenericMessage = (OrVnfmGenericMessage) message;
          virtualNetworkFunctionRecord = orVnfmGenericMessage.getVnfr();
          VNFRecordDependency vnfrDependency = orVnfmGenericMessage.getVnfrd();
          nsrId = orVnfmGenericMessage.getVnfr().getParent_ns_id();

          networkService = getNetworkService(virtualNetworkFunctionRecord.getParent_ns_id());

          // fill the dependency map
          for (Map.Entry<String, DependencyParameters> entry :
              vnfrDependency.getParameters().entrySet()) {
            String sourceType = entry.getKey();
            String sourceName = "";
            for (Map.Entry<String, String> nameTypeEntry : vnfrDependency.getIdType().entrySet()) {
              if (nameTypeEntry.getValue().equals(sourceType)) sourceName = nameTypeEntry.getKey();
            }
            DependencyParameters dependencyParameters = entry.getValue();
            List<String> parameters = new LinkedList<>();
            for (Map.Entry<String, String> pe : dependencyParameters.getParameters().entrySet()) {
              parameters.add(pe.getKey());
            }
            Map<String, List<String>> sourceParams = new HashMap<>();
            sourceParams.put(sourceName, parameters);
            Map<String, Map<String, List<String>>> targetSourceParams = new HashMap<>();
            targetSourceParams.put(virtualNetworkFunctionRecord.getName(), sourceParams);

            networkService.addDependency(
                virtualNetworkFunctionRecord.getName(), sourceName, parameters);
          }

          networkService.setVnfStatus(virtualNetworkFunctionRecord.getName(), "modified");

          nfvMessage =
              VnfmUtils.getNfvMessage(
                  Action.MODIFY,
                  this.modify(orVnfmGenericMessage.getVnfr(), orVnfmGenericMessage.getVnfrd()));
          log.info(
              "After modify of "
                  + virtualNetworkFunctionRecord.getName()
                  + ":\n"
                  + networkService.toString());
          break;
        case START:
          log.info(
              "Received NFVO Message: "
                  + message.getAction()
                  + " for VNFR "
                  + ((OrVnfmStartStopMessage) message).getVirtualNetworkFunctionRecord().getName()
                  + " and following vnfrDep: \n"
                  + ((OrVnfmStartStopMessage) message).getVnfrd());
          orVnfmStartStopMessage = (OrVnfmStartStopMessage) message;
          virtualNetworkFunctionRecord = orVnfmStartStopMessage.getVirtualNetworkFunctionRecord();
          nsrId = orVnfmStartStopMessage.getVirtualNetworkFunctionRecord().getParent_ns_id();

          networkService = getNetworkService(virtualNetworkFunctionRecord.getParent_ns_id());
          networkService.setVnfStatus(virtualNetworkFunctionRecord.getName(), "started");
          networkService.addVnfr(virtualNetworkFunctionRecord);

          log.info(
              "After start of "
                  + virtualNetworkFunctionRecord.getName()
                  + ":\n"
                  + networkService.toString());

          if (networkService.isReadyToDeploy()) {
            deployNetworkService(networkService);
          }

          nfvMessage =
              VnfmUtils.getNfvMessage(
                  Action.START, start(orVnfmStartStopMessage.getVirtualNetworkFunctionRecord()));
          break;
        case RELEASE_RESOURCES:
          log.info(
              "Received NFVO Message: "
                  + message.getAction()
                  + " for VNFR "
                  + ((OrVnfmGenericMessage) message).getVnfr().getName());

          orVnfmGenericMessage = (OrVnfmGenericMessage) message;
          VirtualNetworkFunctionRecord vnfr = orVnfmGenericMessage.getVnfr();
          stopCharm(vnfr.getParent_ns_id(), vnfr.getName());
          networkServiceMap.remove(vnfr.getParent_ns_id());

          nsrId = orVnfmGenericMessage.getVnfr().getParent_ns_id();
          virtualNetworkFunctionRecord = orVnfmGenericMessage.getVnfr();
          nfvMessage =
              VnfmUtils.getNfvMessage(
                  Action.RELEASE_RESOURCES, this.terminate(virtualNetworkFunctionRecord));
          break;
      }

    } catch (Exception e) {
      log.error("ERROR: ", e);
      if (e instanceof VnfmSdkException) {
        VnfmSdkException vnfmSdkException = (VnfmSdkException) e;
        if (vnfmSdkException.getVnfr() != null) {
          log.debug("sending VNFR with version: " + vnfmSdkException.getVnfr().getHbVersion());
          vnfmHelper.sendToNfvo(
              VnfmUtils.getNfvErrorMessage(vnfmSdkException.getVnfr(), vnfmSdkException, nsrId));
          return nfvMessage;
        }
      } else if (e.getCause() instanceof VnfmSdkException) {
        VnfmSdkException vnfmSdkException = (VnfmSdkException) e.getCause();
        if (vnfmSdkException.getVnfr() != null) {
          log.debug("sending VNFR with version: " + vnfmSdkException.getVnfr().getHbVersion());
          vnfmHelper.sendToNfvo(
              VnfmUtils.getNfvErrorMessage(vnfmSdkException.getVnfr(), vnfmSdkException, nsrId));
          return nfvMessage;
        }
      }
      vnfmHelper.sendToNfvo(VnfmUtils.getNfvErrorMessage(virtualNetworkFunctionRecord, e, nsrId));
    }
    return nfvMessage;
  }

  /**
   * Remove a directory.
   *
   * @param directory
   */
  public void removeDirectory(File directory) {
    if (directory.exists() && directory.isDirectory()) {
      File[] files = directory.listFiles();
      if (files != null) {
        for (File file : files) {
          if (file.isDirectory()) removeDirectory(file);
          else file.delete();
        }
      }
    }
    directory.delete();
  }

  /**
   * This method creates a charm based on the received VNFR.
   *
   * @param vnfr
   * @param vnfPackage
   * @throws Exception
   */
  private void createCharm(String nsId, VirtualNetworkFunctionRecord vnfr, VNFPackage vnfPackage)
      throws Exception {
    log.info("Create charm for vnfr " + vnfr.getName());
    (new File("/tmp/openbaton/juju/" + nsId + "/" + vnfr.getName())).mkdirs();
    (new File("/tmp/openbaton/juju/" + nsId + "/" + vnfr.getName() + "/hooks")).mkdirs();
    File metadataYaml =
        new File("/tmp/openbaton/juju/" + nsId + "/" + vnfr.getName() + "/metadata.yaml");
    metadataYaml.createNewFile();

    Map<String, Object> data = new HashMap<String, Object>();
    data.put("name", vnfr.getName());
    data.put("maintainer", vnfr.getVendor());
    data.put("subordinate", "false");
    data.put("summary", "Charm created by Open Baton");
    data.put("description", "Charm created by Open Baton");

    NetworkService networkService = getNetworkService(nsId);
    if (networkService.vnfIsTarget(vnfr.getName())) {
      // create requires section for metadata.yaml
      Map<String, List<String>> sourceMap = networkService.getSourcesOfVnf(vnfr.getName());
      Map<String, Map<String, String>> requiresMap = new HashMap<>();

      for (Map.Entry<String, List<String>> sourceParams : sourceMap.entrySet()) {
        Map<String, String> interfaceMap = new InterfaceMap();
        for (String param : sourceParams.getValue()) interfaceMap.put("interface", param);
        requiresMap.put(sourceParams.getKey(), interfaceMap);
      }
      data.put("requires", requiresMap);
    }

    if (networkService.vnfIsSource(vnfr.getName())) {
      log.info(vnfr.getName() + " is source of a dependency");
      // create provides section for metadata.yaml
      Set<String> providesSet = networkService.getProvidesOfVnf(vnfr.getName());
      Map<String, Map<String, String>> providesMap = new HashMap<>();
      Map<String, String> interfaceMap = new InterfaceMap();
      for (String param : providesSet) {
        interfaceMap.put("interface", param);
      }
      providesMap.put(vnfr.getName(), interfaceMap);
      data.put("provides", providesMap);

      // create relation-joined hooks
      File relationJoined =
          new File(
              "/tmp/openbaton/juju/"
                  + nsId
                  + "/"
                  + vnfr.getName()
                  + "/hooks/"
                  + vnfr.getName()
                  + "-relation-joined");
      relationJoined.createNewFile();

      String variables =
          "#!/bin/bash\necho \"`date '+%H-%M-%S'` "
              + vnfr.getName()
              + ": execute "
              + vnfr.getName()
              + "-relation-joined hook\" >> "
              + scriptLogPath
              + "/"
              + vnfr.getName()
              + "\n";
      List<String> virtuaLinks = new LinkedList<>();
      for (VirtualDeploymentUnit vdu : vnfr.getVdu()) {
        for (VNFComponent vnfc : vdu.getVnfc()) {
          for (VNFDConnectionPoint cp : vnfc.getConnection_point()) {
            virtuaLinks.add(cp.getVirtual_link_reference());
          }
        }
      }
      for (String vl : virtuaLinks)
        variables += ("relation-set " + vl + "=`unit-get private-address`\n");
      // TODO floating ips
      variables += "relation-set hostname=`hostname`\n";

      for (ConfigurationParameter confParam :
          vnfr.getConfigurations().getConfigurationParameters()) {
        variables +=
            "relation-set "
                + vnfr.getType()
                + "_"
                + confParam.getConfKey()
                + "="
                + confParam.getValue()
                + "\n";
      }

      variables += "relation-set allRelationParametersAreReady=true\n";
      variables +=
          "echo \"`date '+%H-%M-%S'` "
              + vnfr.getName()
              + ": finished "
              + vnfr.getName()
              + "-relation-joined hook\" >> "
              + scriptLogPath
              + "/"
              + vnfr.getName()
              + "\n";

      try {
        Files.write(
            Paths.get(relationJoined.getAbsolutePath()),
            variables.getBytes(),
            StandardOpenOption.APPEND);
      } catch (IOException e) {
        log.error("Could not write to relationJoined file");
      }
    }

    (new File("/tmp/openbaton/juju/" + nsId + "/" + vnfr.getName() + "/" + "scripts/")).mkdirs();
    if (vnfPackage.getScriptsLink() != null && !vnfPackage.getScriptsLink().equals("")) {
      downloadGitRepo(nsId, vnfPackage.getScriptsLink(), vnfr.getName());
    } else {
      Set<Script> scripts = vnfPackage.getScripts();
      for (Script script : scripts) {
        File scriptFile =
            new File(
                "/tmp/openbaton/juju/"
                    + nsId
                    + "/"
                    + vnfr.getName()
                    + "/"
                    + "scripts/"
                    + script.getName());
        if (!scriptFile.exists()) scriptFile.createNewFile();
        try {
          Files.write(
              Paths.get(scriptFile.getAbsolutePath()),
              script.getPayload(),
              StandardOpenOption.APPEND);
        } catch (IOException e) {
          log.error("Could not write to script file " + script.getName());
        }
      }
    }

    // file containing the environment variable declarations; can be sourced by scripts
    createEnvironmentVariableFile(nsId, vnfr);

    // create install hook to handle the script path if there is no INSTANTIATE lifecycle event
    boolean installExists = false;
    for (LifecycleEvent le : vnfr.getLifecycle_event()) {
      if (le.getEvent().equals(Event.INSTANTIATE)) {
        installExists = true;
        break;
      }
    }
    if (!installExists) {
      File file = new File("/tmp/openbaton/juju/" + nsId + "/" + vnfr.getName() + "/hooks/install");
      if (!file.exists()) {
        file.createNewFile();
        Files.setPosixFilePermissions(file.toPath(), permissions);
      }
      String fileContent =
          "#!/bin/bash\necho \"`date '+%H-%M-%S'` "
              + vnfr.getName()
              + ": execute install hook\" >> "
              + scriptLogPath
              + "/"
              + vnfr.getName()
              + "\nmkdir -p "
              + scriptPath
              + "\ncp -r scripts/* "
              + scriptPath
              + "\nmkdir -p "
              + scriptLogPath
              + "\necho \"`date '+%H-%M-%S'` "
              + vnfr.getName()
              + ": finished install hook\" >> "
              + scriptLogPath
              + "/"
              + vnfr.getName()
              + "\n";
      try {
        Files.write(
            Paths.get(file.getAbsolutePath()), fileContent.getBytes(), StandardOpenOption.APPEND);
      } catch (IOException e) {
        log.error("Could not write to install file");
      }
    }

    // didn't use one for loop containing all lifecycle checks to ensure the order of processing
    for (LifecycleEvent le : vnfr.getLifecycle_event()) {
      // copy lifecycle scripts into charm TODO remove
      //      copyLifecycleScripts(le, le.getEvent(), vnfr.getName(), nsId);

      if (le.getEvent().equals(Event.INSTANTIATE)) {
        log.debug("Found INSTANTIATE lifecycle event in VNF " + vnfr.getName());

        prepareLifecycleScript(
            "install",
            nsId,
            vnfr.getName(),
            le,
            "mkdir -p "
                + scriptPath
                + "\ncp -r scripts/* "
                + scriptPath
                + "\nmkdir -p "
                + scriptLogPath,
            "");

        if (!networkService.vnfIsTarget(
            vnfr.getName())) { // append configure scripts to the install hook
          File install =
              new File("/tmp/openbaton/juju/" + nsId + "/" + vnfr.getName() + "/hooks/install");
          File runConfigureScripts =
              new File(
                  "/tmp/openbaton/juju/"
                      + nsId
                      + "/"
                      + vnfr.getName()
                      + "/scripts/runConfigureScripts");
          if (!runConfigureScripts.exists()) {
            runConfigureScripts.createNewFile();
            Files.setPosixFilePermissions(runConfigureScripts.toPath(), permissions);
          }
          //TODO
          String fileContent = "bash runConfigureScripts\n";
          try {
            Files.write(
                Paths.get(install.getAbsolutePath()),
                fileContent.getBytes(),
                StandardOpenOption.APPEND);
          } catch (IOException e) {
            log.error("Could not write to install file");
          }
        }
      }
    }

    for (LifecycleEvent le : vnfr.getLifecycle_event()) {
      if (le.getEvent().equals(Event.CONFIGURE)) {
        log.debug("Found CONFIGURE lifecycle event in VNF " + vnfr.getName());
        int numberOfConfigureScripts =
            0; // number of scripts that have to be executed before the start hook may run

        if (!networkService.vnfIsTarget(
            vnfr.getName())) { // append configure scripts to the install hook
          File install =
              new File("/tmp/openbaton/juju/" + nsId + "/" + vnfr.getName() + "/hooks/install");
          File runConfigureScripts =
              new File(
                  "/tmp/openbaton/juju/"
                      + nsId
                      + "/"
                      + vnfr.getName()
                      + "/scripts/runConfigureScripts");
          if (!install.exists()) {
            install.createNewFile();
            Files.setPosixFilePermissions(install.toPath(), permissions);
            if (!runConfigureScripts.exists()) {
              runConfigureScripts.createNewFile();
              Files.setPosixFilePermissions(runConfigureScripts.toPath(), permissions);
              //TODO
              String fileContent = "bash runConfigureScripts\n";
              try {
                Files.write(
                    Paths.get(install.getAbsolutePath()),
                    fileContent.getBytes(),
                    StandardOpenOption.APPEND);
              } catch (IOException e) {
                log.error("Could not write to install file");
              }
            }
          }
          prepareLifecycleScript("runConfigureScripts", nsId, vnfr.getName(), le);

        } else { // create relation-changed hooks for the configure scripts
          List<File> relationChangedList = new LinkedList<>();
          for (String scriptName : le.getLifecycle_events()) {
            boolean convertToRelationHook = false;
            for (VirtualNetworkFunctionRecord virtualNetworkFunctionRecord :
                networkService.getVnfrList()) {
              if (scriptName.startsWith(virtualNetworkFunctionRecord.getType() + "_")) {
                // create relation_changed hook
                File relationChanged =
                    new File(
                        "/tmp/openbaton/juju/"
                            + nsId
                            + "/"
                            + vnfr.getName()
                            + "/hooks/"
                            + virtualNetworkFunctionRecord.getName()
                            + "-relation-changed");
                if (!relationChanged.exists()) {
                  relationChanged.createNewFile();
                  try {
                    Files.write(
                        Paths.get(relationChanged.getAbsolutePath()),
                        ("#!/bin/bash\n"
                                + "if [ -f hooks/finishedRelationChangedHooks/"
                                + relationChanged.getName()
                                + " ]; then\n"
                                + "  echo \"`date '+%H-%M-%S'` "
                                + vnfr.getName()
                                + ": "
                                + relationChanged.getName()
                                + " hook was already executed. Do not run it again.\" >> "
                                + scriptLogPath
                                + "/"
                                + vnfr.getName()
                                + "\n"
                                + "  exit 0\n"
                                + "fi\n"
                                + "export allRelationParametersAreReady=`relation-get allRelationParametersAreReady`\nif [ \"$allRelationParametersAreReady\" != \"true\" ]; then\n  echo \"`date '+%H-%M-%S'` "
                                + vnfr.getName()
                                + ": The relation parameters are not yet set. Abort "
                                + virtualNetworkFunctionRecord.getName()
                                + "-relation-changed hook and try again later.\" >> "
                                + scriptLogPath
                                + "/"
                                + vnfr.getName()
                                + "\n  exit 0\nfi\n"
                                + "echo \"`date '+%H-%M-%S'` "
                                + vnfr.getName()
                                + ": execute "
                                + virtualNetworkFunctionRecord.getName()
                                + "-relation-changed hook"
                                + "\" >> "
                                + scriptLogPath
                                + "/"
                                + vnfr.getName()
                                + "\nsource hooks/paramVariables\nsource hooks/relationVariables\n")
                            .getBytes(),
                        StandardOpenOption.APPEND);
                  } catch (IOException e) {
                    log.error("Could not write to relationChanged file");
                  }
                  relationChangedList.add(relationChanged);
                }
                try {
                  Files.write(
                      Paths.get(relationChanged.getAbsolutePath()),
                      ("pushd scripts \necho \"`date '+%H-%M-%S'` "
                              + vnfr.getName()
                              + ": execute "
                              + scriptName
                              + "\" >> "
                              + scriptLogPath
                              + "/"
                              + vnfr.getName()
                              + "\nbash " // TODO test
                              + scriptName
                              + "\necho \"`date '+%H-%M-%S'` "
                              + vnfr.getName()
                              + ": finished "
                              + scriptName
                              + "\" >> "
                              + scriptLogPath
                              + "/"
                              + vnfr.getName()
                              + "\npopd \ntouch hooks/finishedConfigureScripts/"
                              + scriptName
                              + "\n")
                          .getBytes(),
                      StandardOpenOption.APPEND);
                } catch (IOException e) {
                  log.error("Could not write to relationChanged file");
                }
                convertToRelationHook = true;
                numberOfConfigureScripts++;
              }
            }
            if (!convertToRelationHook) { // append this configure script to the install hook
              File install =
                  new File("/tmp/openbaton/juju/" + nsId + "/" + vnfr.getName() + "/hooks/install");
              File runConfigureScripts =
                  new File(
                      "/tmp/openbaton/juju/"
                          + nsId
                          + "/"
                          + vnfr.getName()
                          + "/scripts/runConfigureScripts");
              if (!install.exists()) {
                install.createNewFile();
                Files.setPosixFilePermissions(install.toPath(), permissions);
                if (!runConfigureScripts.exists()) {
                  runConfigureScripts.createNewFile();
                  Files.setPosixFilePermissions(runConfigureScripts.toPath(), permissions);
                  //TODO
                  String fileContent = "bash runConfigureScripts\n";
                  try {
                    Files.write(
                        Paths.get(install.getAbsolutePath()),
                        fileContent.getBytes(),
                        StandardOpenOption.APPEND);
                  } catch (IOException e) {
                    log.error("Could not write to install file");
                  }
                }
              }
              if (!runConfigureScripts.exists()) runConfigureScripts.createNewFile();
              try {
                Files.write(
                    Paths.get(runConfigureScripts.getAbsolutePath()),
                    ("bash " + scriptName + "\n").getBytes(), // TODO test
                    StandardOpenOption.APPEND);
              } catch (IOException e) {
                log.error("Could not write to runConfigureScripts file");
              }
            }
          }

          // add notification that a relation changed hook was run
          if (relationChangedList != null && !relationChangedList.isEmpty()) {
            (new File(
                    "/tmp/openbaton/juju/"
                        + nsId
                        + "/"
                        + vnfr.getName()
                        + "/hooks/finishedRelationChangedHooks"))
                .mkdirs();
            for (File f : relationChangedList) {
              try {
                Files.write(
                    Paths.get(f.getAbsolutePath()),
                    ("touch hooks/finishedRelationChangedHooks/"
                            + f.getName()
                            + "\necho \"`date '+%H-%M-%S'` "
                            + vnfr.getName()
                            + ": finished "
                            + f.getName()
                            + " hook\" >> "
                            + scriptLogPath
                            + "/"
                            + vnfr.getName()
                            + "\n")
                        .getBytes(), // TODO test
                    StandardOpenOption.APPEND);
              } catch (IOException e) {
                log.error("Could not write to " + f.getName() + " file");
              }
            }
          }

          if (numberOfConfigureScripts
              > 0) { // means we have to create an artificial start script that will run after all the relation-changed hooks finished
            File startAfterDependencies =
                new File(
                    "/tmp/openbaton/juju/"
                        + nsId
                        + "/"
                        + vnfr.getName()
                        + "/scripts/startAfterDependencies");
            (new File(
                    "/tmp/openbaton/juju/"
                        + nsId
                        + "/"
                        + vnfr.getName()
                        + "/hooks/finishedConfigureScripts"))
                .mkdirs();
            startAfterDependencies.createNewFile();
            for (File f : relationChangedList) {
              Files.write(
                  Paths.get(f.getAbsolutePath()),
                  ("echo \"`date '+%H-%M-%S'` "
                          + vnfr.getName()
                          + ": ($(ls -1 hooks/finishedConfigureScripts/ | wc -l)/"
                          + numberOfConfigureScripts
                          + ") scripts finished before executing startAfterDependencies.\" >> "
                          + scriptLogPath
                          + "/"
                          + vnfr.getName()
                          + "\n"
                          + "if [ $(ls -1 hooks/finishedConfigureScripts/ | wc -l) -eq "
                          + numberOfConfigureScripts
                          + " ]; then\necho \"`date '+%H-%M-%S'` "
                          + vnfr.getName()
                          + ": Trigger startAfterDependencies\" >> "
                          + scriptLogPath
                          + "/"
                          + vnfr.getName()
                          + "\nbash hooks/startAfterDependencies; \nfi\n")
                      .getBytes(),
                  StandardOpenOption.APPEND);
            }
          }
        }
      }
    }

    for (LifecycleEvent le : vnfr.getLifecycle_event()) {
      if (le.getEvent().equals(Event.START)) {
        log.debug("Found START lifecycle event in VNF " + vnfr.getName());

        if (networkService.vnfIsTarget(vnfr.getName())) {
          log.info(vnfr.getName() + " is target of a dependency");
          prepareLifecycleScript("startAfterDependencies", nsId, vnfr.getName(), le);
        } else {
          // charm is not target of a relation, you can map the Open Baton start lifecycle to the Juju start hook
          prepareLifecycleScript("start", nsId, vnfr.getName(), le);
        }
      }
    }

    for (LifecycleEvent le : vnfr.getLifecycle_event()) {

      if (le.getEvent().equals(Event.TERMINATE)) {
        log.debug("Found TERMINATE lifecycle event in VNF " + vnfr.getName());
        prepareLifecycleScript("stop", nsId, vnfr.getName(), le);
      }
    }

    // actually write to the metadata.yaml file
    DumperOptions options = new DumperOptions();
    options.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK);
    Yaml yaml = new Yaml(options);
    FileWriter writer = new FileWriter(metadataYaml.getAbsolutePath());
    yaml.dump(data, writer);
  }

  /**
   * Write the lifecycle scripts into a special directory in the charm so that they can be called by
   * the charm hooks. TODO
   *
   * @param fileName
   * @param nsId
   * @param vnfName
   * @param le
   * @throws Exception
   */
  private void prepareLifecycleScript(
      String fileName, String nsId, String vnfName, LifecycleEvent le) throws Exception {
    File file = new File("/tmp/openbaton/juju/" + nsId + "/" + vnfName + "/hooks/" + fileName);
    if (!file.exists()) {
      file.createNewFile();
      Files.setPosixFilePermissions(file.toPath(), permissions);
    }

    String fileContent =
        "#!/bin/bash\necho \"`date '+%H-%M-%S'` "
            + vnfName
            + ": execute "
            + fileName
            + " hook\" >> "
            + scriptLogPath
            + "/"
            + vnfName
            + "\nsource hooks/paramVariables\n";
    fileContent += ("cd scripts\n");
    for (String scriptName : le.getLifecycle_events())
      fileContent +=
          ("echo \"`date '+%H-%M-%S'` "
              + vnfName
              + ": execute "
              + scriptName
              + "\" >> "
              + scriptLogPath
              + "/"
              + vnfName
              + "\nbash "
              + scriptName
              + "\necho \"`date '+%H-%M-%S'` "
              + vnfName
              + ": finished "
              + scriptName
              + "\" >> "
              + scriptLogPath
              + "/"
              + vnfName
              + "\n");
    fileContent +=
        "echo \"`date '+%H-%M-%S'` "
            + vnfName
            + ": finished "
            + fileName
            + " hook\" >> "
            + scriptLogPath
            + "/"
            + vnfName
            + "\n";
    try {
      Files.write(
          Paths.get(file.getAbsolutePath()), fileContent.getBytes(), StandardOpenOption.APPEND);
    } catch (IOException e) {
      log.error("Could not write to " + fileName + " file");
    }
  }

  private void prepareLifecycleScript(
      String fileName,
      String nsId,
      String vnfName,
      LifecycleEvent le,
      String preamble,
      String postamble)
      throws IOException {
    File file = new File("/tmp/openbaton/juju/" + nsId + "/" + vnfName + "/hooks/" + fileName);
    if (!file.exists()) {
      file.createNewFile();
      Files.setPosixFilePermissions(file.toPath(), permissions);
    }

    String fileContent =
        "#!/bin/bash\n"
            + preamble
            + "\necho \"`date '+%H-%M-%S'` "
            + vnfName
            + ": execute "
            + fileName
            + " hook\" >> "
            + scriptLogPath
            + "/"
            + vnfName
            + "\nsource hooks/paramVariables\n";
    fileContent += ("cd scripts\n");
    for (String scriptName : le.getLifecycle_events())
      fileContent +=
          ("echo \"`date '+%H-%M-%S'` "
              + vnfName
              + ": execute "
              + scriptName
              + "\" >> "
              + scriptLogPath
              + "/"
              + vnfName
              + "\nbash "
              + scriptName
              + "\necho \"`date '+%H-%M-%S'` "
              + vnfName
              + ": finished "
              + scriptName
              + "\" >> "
              + scriptLogPath
              + "/"
              + vnfName);
    fileContent +=
        postamble
            + "\necho \"`date '+%H-%M-%S'` "
            + vnfName
            + ": finished "
            + fileName
            + " hook\" >> "
            + scriptLogPath
            + "/"
            + vnfName
            + "\n";
    try {
      Files.write(
          Paths.get(file.getAbsolutePath()), fileContent.getBytes(), StandardOpenOption.APPEND);
    } catch (IOException e) {
      log.error("Could not write to " + fileName + " file");
    }
  }

  /**
   * Create the files containing environment variable declarations that can be sourced by the
   * scripts.
   *
   * @param nsId
   * @param vnfr
   * @throws IOException
   */
  private void createEnvironmentVariableFile(String nsId, VirtualNetworkFunctionRecord vnfr)
      throws IOException {
    // create a file containing all the variables that should be available in the scripts
    File paramVariables =
        new File("/tmp/openbaton/juju/" + nsId + "/" + vnfr.getName() + "/hooks/paramVariables");
    if (!paramVariables.exists()) {
      paramVariables.createNewFile();
      Files.setPosixFilePermissions(paramVariables.toPath(), permissions);
    }
    try {
      log.info("Write SCRIPTS_PATH to paramVariables file");
      Files.write(
          Paths.get(paramVariables.getAbsolutePath()),
          ("export SCRIPTS_PATH=" + scriptPath + "\n").getBytes(),
          StandardOpenOption.APPEND);
    } catch (IOException e) {
      log.error("Could not write SCRIPTS_PATH paramVariables file");
    }
    File relationVariables =
        new File("/tmp/openbaton/juju/" + nsId + "/" + vnfr.getName() + "/hooks/relationVariables");
    if (!relationVariables.exists()) {
      relationVariables.createNewFile();
      Files.setPosixFilePermissions(relationVariables.toPath(), permissions);
    }
    // begin with the configurations:
    if (vnfr.getConfigurations() != null) {
      Configuration configuration = vnfr.getConfigurations();
      String variables = "";
      for (ConfigurationParameter parameter : configuration.getConfigurationParameters()) {
        variables += "export " + parameter.getConfKey() + "=" + parameter.getValue() + "\n";
      }
      try {
        log.info("Write variables to paramVariables file: " + variables);
        Files.write(
            Paths.get(paramVariables.getAbsolutePath()),
            variables.getBytes(),
            StandardOpenOption.APPEND);
      } catch (IOException e) {
        log.error("Could not write configuration variables to paramVariables file");
      }
      try {
        log.info("Write variables to relationVariables file: " + variables);
        Files.write(
            Paths.get(relationVariables.getAbsolutePath()),
            variables.getBytes(),
            StandardOpenOption.APPEND);
      } catch (IOException e) {
        log.error("Could not write configuration variables to relationVariables file");
      }
    }

    // now the out-of-the-box variables <netname>, <netname>_floatingIp and hostname of the VNFD itself
    String variables = "";
    List<String> virtualLinks = new LinkedList<>();
    for (VirtualDeploymentUnit vdu : vnfr.getVdu()) {
      for (VNFComponent vnfc : vdu.getVnfc()) {
        for (VNFDConnectionPoint cp : vnfc.getConnection_point()) {
          virtualLinks.add(cp.getVirtual_link_reference());
        }
      }
    }
    for (String vl : virtualLinks) variables += ("export " + vl + "=`unit-get private-address`\n");
    // TODO floating ips
    variables += "export hostname=`hostname`\n";
    try {
      log.info("Write variables to paramVariables file: " + variables);
      Files.write(
          Paths.get(paramVariables.getAbsolutePath()),
          variables.getBytes(),
          StandardOpenOption.APPEND);
    } catch (IOException e) {
      log.error("Could not write out-of-the-box variables to paramVariables file");
    }

    // and now the out-of-the-box variables <netname>, <netname>_floatingIp and hostname of the other VNFDs
    variables = "";
    for (String sourceVnfName : getNetworkService(nsId).getSourcesNames(vnfr.getName())) {
      VirtualNetworkFunctionRecord sourceVnfr =
          getNetworkService(nsId).getVnfrByName(sourceVnfName);

      List<String> virtuaLinks = new LinkedList<>();
      for (VirtualDeploymentUnit vdu : sourceVnfr.getVdu()) {
        for (VNFComponent vnfc : vdu.getVnfc()) {
          for (VNFDConnectionPoint cp : vnfc.getConnection_point()) {
            virtuaLinks.add(cp.getVirtual_link_reference());
          }
        }
      }

      for (String vl : virtuaLinks)
        variables += ("export " + sourceVnfr.getType() + "_" + vl + "=`relation-get " + vl + "`\n");
      // TODO floating ips
      variables += "export " + sourceVnfr.getType() + "_" + "hostname=`relation-get hostname`\n";

      for (ConfigurationParameter confParam :
          sourceVnfr.getConfigurations().getConfigurationParameters()) {
        variables +=
            "export "
                + sourceVnfr.getType()
                + "_"
                + confParam.getConfKey()
                + "=`relation-get "
                + sourceVnfr.getType()
                + "_"
                + confParam.getConfKey()
                + "`\n";
      }

      try {
        log.info("Write variables to relationVariables file: " + variables);
        Files.write(
            Paths.get(relationVariables.getAbsolutePath()),
            variables.getBytes(),
            StandardOpenOption.APPEND);
      } catch (IOException e) {
        log.error("Could not write configuration variables to relationVariables file");
      }
    }
  }

  /**
   * Download the git repository containing the scripts for the VNFD.
   *
   * @param scriptsLink
   * @param vnfdName
   */
  private void downloadGitRepo(String nsId, String scriptsLink, String vnfdName) {
    log.info("Start fetching git repository from " + scriptsLink + " for VNFD " + vnfdName);
    ProcessBuilder pb =
        new ProcessBuilder(
            "/bin/bash",
            "-c",
            "cd /tmp/openbaton/juju/"
                + nsId
                + "/"
                + vnfdName
                + "/scripts"
                + " && git clone "
                + scriptsLink
                + " .");
    Process execute = null;
    int exitStatus = -1;
    try {
      execute = pb.redirectOutput(ProcessBuilder.Redirect.INHERIT).start();
      exitStatus = execute.waitFor();
    } catch (IOException e) {
      e.printStackTrace();
    } catch (InterruptedException e) {
      e.printStackTrace();
    }
    if (exitStatus == 0) {
      log.info("Successfully fetched git repository");
      (new File("/tmp/openbaton/juju/" + nsId + "/" + vnfdName + "/scripts/.git")).delete();
    } else log.error("Could not fetch git repository");
  }

  /**
   * Copy the lifecycle scripts into the correct charm.
   *
   * @param le
   * @param event
   * @param vnfdName
   * @return
   * @throws Exception
   */
  private void copyLifecycleScripts(LifecycleEvent le, Event event, String vnfdName, String nsId)
      throws Exception {
    log.info("Copy scripts for lifecycle event " + event.name());

    File scriptsFolder = new File("/tmp/openbaton/juju/" + nsId + "/scripts/" + vnfdName);
    File[] listOfFiles = scriptsFolder.listFiles();
    if (listOfFiles.length > 1)
      throw new Exception(
          "There is more than one folder in /tmp/openbaton/juju/scripts/" + vnfdName);
    else if (listOfFiles.length < 1)
      throw new Exception(
          "No script folder found in /tmp/openbaton/juju/" + nsId + "/scripts/" + vnfdName);

    String gitFolderPath = "";

    for (int i = 0; i < listOfFiles.length; i++) {
      if (listOfFiles[i].isFile()) {
        throw new Exception(
            "Git Repository expected but just found a normal file: " + listOfFiles[i].getName());
      } else if (listOfFiles[i].isDirectory()) {
        gitFolderPath = listOfFiles[i].getPath();
      }
    }
    log.info("Found git folder " + gitFolderPath);

    String dirName = event.name().toLowerCase() + "Scripts";
    File scriptDir = new File("/tmp/openbaton/juju/" + nsId + "/" + vnfdName + "/hooks/" + dirName);
    if (!scriptDir.exists())
      (new File(
              "/tmp/openbaton/juju/"
                  + nsId
                  + "/"
                  + vnfdName
                  + "/hooks/"
                  + le.getEvent().name().toLowerCase()
                  + "Scripts"))
          .mkdirs();

    for (String script : le.getLifecycle_events()) {
      Files.copy(
          Paths.get(gitFolderPath + "/" + script),
          Paths.get(
              "/tmp/openbaton/juju/" + nsId + "/" + vnfdName + "/hooks/" + dirName + "/" + script),
          StandardCopyOption.COPY_ATTRIBUTES);
    }
  }

  /**
   * Deploy a NetworkService. That means create the charm directories, deploy them, add charm
   * relations corresponding to the dependencies and remove the created directory again.
   *
   * @param networkService
   * @throws Exception
   */
  private void deployNetworkService(NetworkService networkService) throws Exception {
    log.info("Deploy the NetworkService with id " + networkService.getId());
    // create charms
    for (VirtualNetworkFunctionRecord vnfr : networkService.getVnfrList()) {
      // if the vnfr should come from the charm store, you don't need to create a charm
      if (!networkService.getCharms().contains(vnfr.getName()))
        createCharm(networkService.getId(), vnfr, networkService.getVnfPackage(vnfr.getName()));
    }
    // deploy charms
    for (VirtualNetworkFunctionRecord vnfr : networkService.getVnfrList()) {
      if (networkService.getCharms().contains(vnfr.getName())) {
        deployCharmFromCharmStore(vnfr.getName());
      } else {
        int numUnits = 0;
        for (VirtualDeploymentUnit vdu : vnfr.getVdu()) {
          numUnits += vdu.getVnfc().size();
        }
        deployCharm(vnfr.getParent_ns_id(), vnfr.getName(), numUnits);
      }
    }
    // set relations
    for (VirtualNetworkFunctionRecord vnfr : networkService.getVnfrList()) {
      if (networkService.vnfIsTarget(vnfr.getName())) {
        for (String source : networkService.getSourcesNames(vnfr.getName())) {
          addRelation(source, vnfr.getName());
        }
      }
    }

    removeDirectory(new File("/tmp/openbaton/juju/" + networkService.getId()));
  }

  /**
   * Deploy the charm from the created directory.
   *
   * @param nsId
   * @param vnfr
   * @param numberOfUnits
   * @throws VnfmSdkException
   */
  private void deployCharm(String nsId, String vnfr, int numberOfUnits) throws VnfmSdkException {
    ProcessBuilder pb;
    Process execute = null;
    int exitStatus = -1;

    //        bootstrapJuju();
    log.debug(
        "juju deploy /tmp/openbaton/juju/"
            + nsId
            + "/"
            + vnfr
            + " -n "
            + numberOfUnits
            + " --series="
            + series
            + "; juju expose");
    pb =
        new ProcessBuilder(
            "/bin/bash",
            "-c",
            "juju deploy /tmp/openbaton/juju/"
                + nsId
                + "/"
                + vnfr
                + " -n "
                + numberOfUnits
                + " --series="
                + series
                + "; juju expose "
                + vnfr);
    try {
      execute = pb.redirectOutput(ProcessBuilder.Redirect.INHERIT).start();
      exitStatus = execute.waitFor();
    } catch (IOException e) {
      e.printStackTrace();
    } catch (InterruptedException e) {
      e.printStackTrace();
    }
    if (exitStatus == 0) {
      log.info("Successfully deployed " + numberOfUnits + " units of vnfr " + vnfr);
    } else {
      log.error("Could not deploy vnfr " + vnfr);
      throw new VnfmSdkException("Could not deploy vnfr " + vnfr);
    }
  }

  /**
   * Deploys a charm from the charm store. The passed name is the name of the charm.
   *
   * @param charmName
   * @throws VnfmSdkException
   */
  private void deployCharmFromCharmStore(String charmName) throws VnfmSdkException {
    ProcessBuilder pb;
    Process execute = null;
    int exitStatus = -1;
    log.debug("juju deploy " + charmName);
    pb =
        new ProcessBuilder(
            "/bin/bash", "-c", "juju deploy " + charmName + "; juju expose " + charmName);
    try {
      execute = pb.redirectOutput(ProcessBuilder.Redirect.INHERIT).start();
      exitStatus = execute.waitFor();
    } catch (IOException e) {
      e.printStackTrace();
    } catch (InterruptedException e) {
      e.printStackTrace();
    }
    if (exitStatus == 0) {
      log.info("Successfully deployed vnf " + charmName + " from the juju charm store");
    } else {
      log.error("Could not deploy vnf " + charmName + " from the juju charm store");
      throw new VnfmSdkException(
          "Could not deploy vnf " + charmName + " from the juju charm store");
    }
  }

  /**
   * Stop the charm and delete the created instance. This will be triggered by a RELEASE_RESOURCES
   * message.
   *
   * @param nsId
   * @param vnfName
   * @throws VnfmSdkException
   */
  private void stopCharm(String nsId, String vnfName) throws VnfmSdkException {
    ProcessBuilder pb;
    Process execute = null;
    int exitStatus = -1;
    log.debug("juju remove-application " + vnfName);
    pb = new ProcessBuilder("/bin/bash", "-c", "juju remove-application " + vnfName);
    try {
      execute = pb.redirectOutput(ProcessBuilder.Redirect.INHERIT).start();
      exitStatus = execute.waitFor();
    } catch (IOException e) {
      e.printStackTrace();
    } catch (InterruptedException e) {
      e.printStackTrace();
    }
    if (exitStatus == 0) {
      log.info("Stopped charm for VNF " + vnfName);
    } else {
      log.error("Could not stop VNF " + vnfName);
      throw new VnfmSdkException("Could not stop VNF " + vnfName);
    }
  }

  /**
   * Add a relation between two charms.
   *
   * @param source
   * @param target
   * @throws VnfmSdkException
   */
  private void addRelation(String source, String target) throws VnfmSdkException {
    ProcessBuilder pb;
    Process execute = null;
    int exitStatus = -1;
    pb =
        new ProcessBuilder(
            "/bin/bash",
            "-c",
            "juju add-relation " + target + ":" + source + " " + source + ":" + source);
    try {
      execute = pb.redirectOutput(ProcessBuilder.Redirect.INHERIT).start();
      exitStatus = execute.waitFor();
    } catch (IOException e) {
      e.printStackTrace();
    } catch (InterruptedException e) {
      e.printStackTrace();
    }
    if (exitStatus == 0) {
      log.info("Added relation between source " + source + " and target " + target);
    } else {
      log.error("Could not add relation between source " + source + " and target " + target);
      throw new VnfmSdkException(
          "Could not add relation between source " + source + " and target " + target);
    }
  }

  /**
   * Get a NetworkService object from the networkServiceMap. If it does not contain the requested
   * NetworkService yet, create and add it.
   *
   * @param id
   * @return the requested NetworkService
   */
  private synchronized NetworkService getNetworkService(String id) {
    if (networkServiceMap.containsKey(id)) return networkServiceMap.get(id);
    else {
      NetworkService networkService = new NetworkService();
      networkServiceMap.put(id, networkService);
      networkService.setId(id);
      return networkService;
    }
  }

  @Override
  public VirtualNetworkFunctionRecord instantiate(
      VirtualNetworkFunctionRecord virtualNetworkFunctionRecord,
      Object scripts,
      Map<String, Collection<VimInstance>> vimInstances)
      throws Exception {
    return virtualNetworkFunctionRecord;
  }

  @Override
  public void query() {}

  @Override
  public void checkInstantiationFeasibility() {}

  @Override
  public VirtualNetworkFunctionRecord heal(
      VirtualNetworkFunctionRecord virtualNetworkFunctionRecord,
      VNFCInstance component,
      String cause)
      throws Exception {
    return virtualNetworkFunctionRecord;
  }

  @Override
  public VirtualNetworkFunctionRecord updateSoftware(
      Script script, VirtualNetworkFunctionRecord virtualNetworkFunctionRecord) throws Exception {
    return virtualNetworkFunctionRecord;
  }

  @Override
  public VirtualNetworkFunctionRecord modify(
      VirtualNetworkFunctionRecord virtualNetworkFunctionRecord, VNFRecordDependency dependency)
      throws Exception {
    return virtualNetworkFunctionRecord;
  }

  @Override
  public void upgradeSoftware() {}

  @Override
  public VirtualNetworkFunctionRecord terminate(
      VirtualNetworkFunctionRecord virtualNetworkFunctionRecord) throws Exception {
    return virtualNetworkFunctionRecord;
  }

  @Override
  public void handleError(VirtualNetworkFunctionRecord virtualNetworkFunctionRecord) {}

  @Override
  public VirtualNetworkFunctionRecord start(
      VirtualNetworkFunctionRecord virtualNetworkFunctionRecord) throws Exception {
    return virtualNetworkFunctionRecord;
  }

  @Override
  public VirtualNetworkFunctionRecord stop(
      VirtualNetworkFunctionRecord virtualNetworkFunctionRecord) throws Exception {
    return virtualNetworkFunctionRecord;
  }

  @Override
  public VirtualNetworkFunctionRecord startVNFCInstance(
      VirtualNetworkFunctionRecord virtualNetworkFunctionRecord, VNFCInstance vnfcInstance)
      throws Exception {
    return virtualNetworkFunctionRecord;
  }

  @Override
  public VirtualNetworkFunctionRecord stopVNFCInstance(
      VirtualNetworkFunctionRecord virtualNetworkFunctionRecord, VNFCInstance vnfcInstance)
      throws Exception {
    return virtualNetworkFunctionRecord;
  }

  @Override
  public VirtualNetworkFunctionRecord configure(
      VirtualNetworkFunctionRecord virtualNetworkFunctionRecord) throws Exception {
    return virtualNetworkFunctionRecord;
  }

  @Override
  public VirtualNetworkFunctionRecord resume(
      VirtualNetworkFunctionRecord virtualNetworkFunctionRecord,
      VNFCInstance vnfcInstance,
      VNFRecordDependency dependency)
      throws Exception {
    return virtualNetworkFunctionRecord;
  }

  @Override
  public VirtualNetworkFunctionRecord scale(
      Action scaleInOrOut,
      VirtualNetworkFunctionRecord virtualNetworkFunctionRecord,
      VNFComponent component,
      Object scripts,
      VNFRecordDependency dependency)
      throws Exception {
    return virtualNetworkFunctionRecord;
  }

  @Override
  public void NotifyChange() {}

  public static void main(String[] args) {
    SpringApplication.run(JujuVnfm.class, args);
  }

  public void setScriptPath(String scriptPath) {
    this.scriptPath = scriptPath;
  }

  public String getScriptPath() {
    return this.scriptPath;
  }
}

// #Sireum

package org.sireum.anvil

import org.sireum._
import org.sireum.anvil.Context.SandboxInstallationContext
import org.sireum.ops.StringOps

object AnvilSandbox {

  def install(context: SandboxInstallationContext): Z = {
    val workspace = context.workspace
    val scriptOrder: ISZ[Os.Path] = ISZ(
      workspace.fixDashScript,
      workspace.installDependenciesScript,
      workspace.installPetalinuxScript,
      workspace.installVivadoScript,
      workspace.installSireumScript
    )

    // Vagrantfile structure inspired by:
    // https://github.com/mitre-cyber-academy/2019-ectf-vagrant/
    def createVagrantFile(): Unit = {
      val vmBox: String = "bento/ubuntu-16.04"
      val vmBoxVersion: String = "201806.08.0"
      val sshShell: String = "bash"

      def installPlugins(plugins: ISZ[String]): ST = {
        return st"""
                   |# Check for missing plugins
                   |# based on https://stackoverflow.com/a/51925021
                   |if ARGV[0] != 'plugin'
                   |  required_plugins = [${(for (plugin <- plugins) yield st"\"$plugin\"", ", ")}]
                   |  plugins_to_install = required_plugins.select { |plugin| not Vagrant.has_plugin? plugin }
                   |  if not plugins_to_install.empty?
                   |    puts "Vagrant is installing plugins: #{plugins_to_install.join(' ')}"
                   |    if system "vagrant plugin install #{plugins_to_install.join(' ')}"
                   |      exec "vagrant #{ARGV.join(' ')}"
                   |    else
                   |      abort "An error occurred during plugin installation. Vagrant will now abort."
                   |    end
                   |  end
                   |end
                   |"""
      }

      def verifyInstallers(files: ISZ[Os.Path]): ST = {
        // add checks for each installer selected by user
        def guard(file: Os.Path): ST = {
          return st"""
                     |# confirms installation of ${file.name}
                     |if !File.file?("./${workspace.root.relativize(file)}")
                     |  raise "Error: $file was included but cannot be found for provisioning."
                     |end
                     |"""
        }
        return st"${(for (file <- files) yield guard(file), "\n")}"
      }

      def configureVagrant(): ST = {
        val config = "config" // name of config's lambda parameter

        def disk(): ST = {
          val size: String = context.disksize()

          // disks & partitions
          val sda: ST = st"/dev/sda"
          val p1: Z = z"1" // sda1 (configured for bento/ubuntu-16.04)
          val p2: Z = z"2" // sda2 (configured for bento/ubuntu-16.04)
          val p5: Z = z"5" // sda5 (configured for bento/ubuntu-16.04)

          // commands
          val fdiskl: ST = st"fdisk -l"
          val umount: Z => ST = (p: Z) => st"umount $sda$p"
          val parted: Z => ST = (p: Z) => st"parted $sda resizepart $p 100%"
          val pvresize: Z => ST = (p: Z) => st"pvresize $sda$p"
          val lvresize: ST = st"lvresize -rl +100%FREE /dev/mapper/vagrant--vg-root"
          val mount: Z => ST = (p: Z) => st"mount $sda$p"

          // script to inflate actual size to growable size (otherwise growth will not occur during install)
          val script: ISZ[ST] = ISZ(
            fdiskl, // print info
            umount(p1),
            parted(p2), parted(p5), // resize partition
            pvresize(p2), pvresize(p5), // resize physical volume to recognize partition changes
            lvresize, // resize logical volume to match increased filesystem
            mount(p1),
            fdiskl // print info
          )

          return st"""
                     |# Extend disk size, based on:
                     |#   - https://github.com/sprotheroe/vagrant-disksize/issues/37#issuecomment-573349769
                     |#   - https://openattic.org/posts/resize-lvm-inside-an-extended-partition/
                     |$config.disksize.size = '$size'
                     |$config.vm.provision "shell", inline: <<-SHELL
                     |  ${(for (cmd <- script) yield st"sudo $cmd", "\n")}
                     |SHELL
                     |"""
        }

        def vm(): ST = {
          return st"""
                     |$config.vm.box = "$vmBox"
                     |$config.vm.box_version = "$vmBoxVersion"
                     |$config.vm.hostname = "${context.hostname}"
                     |"""
        }

        def shell(): ST = {
          return st"""
                     |# Enable X11 forwarding.
                     |$config.ssh.shell = "$sshShell"
                     |$config.ssh.forward_x11 = true
                     |$config.ssh.forward_agent = true
                     |$config.ssh.username = '${context.username}'
                     |$config.ssh.password = '${context.password}'
                     |$config.ssh.insert_key = true
                     |"""
        }

        def virtualbox(): ST = {
          // configuration
          val plugin: ISZ[(String, String)] = ISZ(
            ("auto_update", "false"),
            ("no_remote", "true"))
          val vm: ISZ[(String, String)] = ISZ(
            ("name", st"\"${context.vmName()}\"".render),
            ("gui", context.enableGUI()),
            ("cpus", context.numCPUs()),
            ("memory", context.memorySizeMB()))
          val modifyvm: ISZ[(String, String)] = ISZ(
            ("graphicscontroller", context.graphicsController()),
            ("accelerate3d", "on"),
            ("vram", context.vramSize()),
            ("usb", "off"),
            ("usbxhci", "off"),
            ("usbehci", "off"))
          // substitution
          val arg = "v" // name we assign virtualbox config in code
          // transformation
          val pst = (kv: (String, String)) => st"$config.vbguest.${kv._1} = ${kv._2}"
          val vst = (kv: (String, String)) => st"$arg.${kv._1} = ${kv._2}"
          val mst = (kv: (String, String)) => st"$arg.customize [\"modifyvm\", :id, \"--${kv._1}\", \"${kv._2}\"]"
          return st"""
                     |# Fix for vagrant-vbguest
                     |${(plugin.map(pst), "\n")}
                     |# VirtualBox-specific configuration.
                     |$config.vm.provider "virtualbox" do |$arg|
                     |  ${(vm.map(vst) ++ modifyvm.map(mst), "\n")}
                     |end
                     |"""
        }

        def scripts(): ST = {
          val prefix: ST = st"$config.vm.provision \"shell\""

          def echo(message: String): ST = {
            return st"$prefix, inline: \"echo '$message'\""
          }

          def execute(script: Os.Path): ST = {
            val path = workspace.root.relativize(script).value
            return st"$prefix, privileged: false, path: \"./$path\""
          }

          return st"""
                     |${echo(string"Provisioning started, this may take a few hours.")}
                     |${(for (script <- scriptOrder) yield execute(script), "\n")}
                     |${echo(string"Provisioning complete, please reboot the machine now.")}
                     |"""
        }

        def sync(): ST = {
          return st"""
                     |  # sync root sandbox root to /vagrant on remote
                     |  $config.vm.synced_folder "./", "/vagrant"
                     |"""
        }

        return st"""
                   |Vagrant.configure(2) do |$config|
                   |  ${(ISZ(vm(), disk(), shell(), virtualbox(), sync(), scripts()), "\n")}
                   |end
                   |"""
      }

      val optToSeq: (Option[Os.Path] => ISZ[Os.Path] @pure) = (o: Option[Os.Path]) => o match {
        case Some(value) => ISZ(value)
        case None() => ISZ[Os.Path]()
      }

      val text = st"""
                     |# -*- mode: ruby -*-
                     |# vi: set ft=ruby :
                     |${installPlugins(ISZ("vagrant-disksize", "vagrant-vbguest"))}
                     |${verifyInstallers(ISZ(context.petalinuxInstallerPath, context.xilinxUnifiedPath).flatMap(optToSeq))}
                     |${configureVagrant()}
                     |"""

      val vagrantFile = mkdirSafe(workspace.root / "Vagrantfile")
      vagrantFile.writeOver(text.render)
    }

    def createScripts(): Unit = {

      /*
       * Ubuntu's default shell ("dash") breaks some Xilinx tools. This script replaces sets the default shell to bash.
       *
       * see: http://manpages.ubuntu.com/manpages/xenial/man1/sh.1.html
       * see: https://www.xilinx.com/support/documentation/sw_manuals/xilinx2020_1/ug1144-petalinux-tools-reference-guide.pdf#unique_26 (fourth bullet under "Installation Requirements")
       */
      def fixDashScript(): ST = {
        return st"""
                 |# replaces ubuntu's dash with bash (fixes bugs in some Xilinx scripts)
                 |# see: https://www.xilinx.com/support/documentation/sw_manuals/xilinx2020_1/ug1144-petalinux-tools-reference-guide.pdf#unique_26
                 |
                 |# make bash the default shell
                 |sudo rm /bin/sh
                 |sudo ln -s /bin/bash /bin/sh
                 |
                 |# turn default value of "dash as system shell" setting to false (for dkpg-configure)
                 |echo "dash dash/sh boolean false" | sudo debconf-set-selections
                 |# then apply this setting noninteractively
                 |sudo dpkg-reconfigure --frontend noninteractive dash
                 |"""
      }

      /*
       * Installs Petalinux.
       */
      def installPetalinuxScript(installerFileName: String): ST = {
        val installer: ST = st"/${context.username}/${workspace.root.relativize(workspace.downloads).value}/$installerFileName"
        val remoteInstallerDir: ST = st"/home/${context.username}/Downloads"
        val remoteTargetDir: ST = st"/opt/pkg/petalinux"
        return st"""
                   |# SETUP DIRECTORIES
                   |sudo rm -rf $remoteTargetDir
                   |sudo mkdir -p $remoteTargetDir
                   |sudo chown ${context.username} $remoteTargetDir
                   |
                   |# COPY
                   |# more reliable to copy into linux and install within
                   |mkdir -p $remoteInstallerDir
                   |cp $installer $remoteInstallerDir
                   |
                   |# RUN INSTALLER
                   |# must not be root when running installer (enforced by installer). Use permissions instead.
                   |sudo chmod a+x $remoteInstallerDir/$installerFileName
                   |sudo chown -R ${context.username} $remoteInstallerDir
                   |yes | $remoteInstallerDir/$installerFileName --platform arm --dir $remoteTargetDir
                   |
                   |# load env on reboot
                   |echo 'alias petalinuxenv="source $remoteTargetDir/${(context.petalinuxSourceScriptRelativePath, "/")}"' >> ${"$"}HOME/.bashrc
                   |
                   |# CLEAN TEMPORARY FILES
                   |# cleanup
                   |rm $remoteInstallerDir/$installerFileName
                   |"""
      }

      /*
       * Installs Vivado and Vivado HLS.
       */
      def installVivadoScript(zippedInstallerFileName: String): ST = {
        val sharedZipped: ST = st"/${context.username}/${workspace.root.relativize(workspace.downloads).value}/$zippedInstallerFileName"
        val unzippedInstallerFileName: String = removeExtension(zippedInstallerFileName, "tar.gz")
        val remoteInstallerDir: ST = st"/home/${context.username}/Downloads"
        val remoteTargetDir: ST = st"/opt/pkg/vivado"
        return st"""
                   |# SETUP DIRECTORIES
                   |sudo rm -rf $remoteTargetDir
                   |sudo mkdir -p $remoteTargetDir
                   |sudo chown ${context.username} $remoteTargetDir
                   |
                   |# COPY
                   |echo "copying vivado installer..."
                   |mkdir -p $remoteInstallerDir
                   |cp $sharedZipped $remoteInstallerDir
                   |echo "DONE"
                   |
                   |# UNZIP
                   |echo "unzipping vivado installer..."
                   |tar xvzf $remoteInstallerDir/$zippedInstallerFileName -C $remoteInstallerDir/
                   |echo "DONE"
                   |
                   |# RUN INSTALLER
                   |echo "running vivado installer..."
                   |$remoteInstallerDir/$unzippedInstallerFileName/xsetup --agree XilinxEULA,3rdPartyEULA,WebTalkTerms --batch Install --edition "Vivado HL WebPACK" --location "$remoteTargetDir" --product "Vivado"
                   |echo "DONE"
                   |
                   |# CREATE ENV SHORTCUT
                   |echo "creating vivadoenv shortcut..."
                   |echo 'alias vivadoenv="source $remoteTargetDir/${(context.xilinxUnifiedSourceScriptRelativePath, "/")}"' >> ${"$"}HOME/.bashrc
                   |echo "DONE"
                   |
                   |# CLEAN TEMPORARY FILES
                   |echo "deleting copied installer tarball..."
                   |rm $remoteInstallerDir/$zippedInstallerFileName
                   |echo "DONE"
                   |echo "deleting extracted installer files..."
                   |rm -rf $remoteInstallerDir/$unzippedInstallerFileName
                   |echo "DONE"
                   |"""
      }

      /*
       * Installs dependencies needed for Petalinux Tools. These will vary per version and target os.
       *
       * For an example see UG1144, Table 2: Packages and Linux Workstation Environments
       * https://www.xilinx.com/support/documentation/sw_manuals/xilinx2020_1/ug1144-petalinux-tools-reference-guide.pdf#unique_26
       */
      def installDependenciesScript(): ST = {
        val dependencies: ISZ[String] = context.petalinuxDependencies
        return st"""
            |sudo apt-get update
            |sudo apt-get install --fix-missing --yes ${(dependencies, " ")}
            |sudo apt-get install --yes ${(dependencies, " ")}
            |"""
      }

      /*
       * Installs Sireum.
       *
       * see: https://github.com/sireum/kekinian#git-source-distribution
       */
      def installSireumScript(): ST = {
        val remoteTargetDir: ST = st"/opt/pkg/sireum"
        return st"""
                   |# SETUP DIRECTORIES
                   |sudo rm -rf $remoteTargetDir
                   |sudo mkdir -p $remoteTargetDir
                   |sudo chown vagrant $remoteTargetDir
                   |
                   |# CLONE
                   |cd $remoteTargetDir && git clone --recursive https://github.com/sireum/kekinian
                   |
                   |# RUN INSTALLER
                   |cd $remoteTargetDir && kekinian/bin/build.cmd setup
                   |
                   |# SETUP ENV
                   |echo 'SIREUM_HOME=$remoteTargetDir/kekinian' >> ${"$"}HOME/.bashrc
                   |"""
      }

      // write scripts
      writeOverSafe(workspace.fixDashScript, fixDashScript().render)
      writeOverSafe(workspace.installDependenciesScript, installDependenciesScript().render)

      context.petalinuxInstallerPath.foreach((p: Os.Path) => {
        val petalinuxInstallerFilename: String = p.name
        p.copyOverTo(workspace.downloads / petalinuxInstallerFilename)
        writeOverSafe(workspace.installPetalinuxScript, installPetalinuxScript(petalinuxInstallerFilename).render)
      })

      context.xilinxUnifiedPath.foreach((p: Os.Path) => {
        val xilinxUnifiedTarballFilename: String = p.name
        p.copyOverTo(workspace.downloads / xilinxUnifiedTarballFilename)
        writeOverSafe(workspace.installVivadoScript, installVivadoScript(xilinxUnifiedTarballFilename).render)
      })

      if (context.installSireum) {
        writeOverSafe(workspace.installSireumScript, installSireumScript().render)
      }
    }

    // write Vagrantfile and supporting scripts
    createVagrantFile()
    createScripts()

    // VAGRANT FILE CREATION
    return context.localSandboxProc(ISZ("vagrant", "up")).exitCode
  }

  def mkdirSafe(file: Os.Path): Os.Path = {
    if (!file.exists) {
      file.mkdirAll()
    }
    return file
  }

  def writeOverSafe(file: Os.Path, content: String): Os.Path = {
    mkdirSafe(file).writeOver(content)
    return file
  }

  def removeExtension(s: String, ext: String): String = {
    val extSize = StringOps(st".$ext".render).size
    return StringOps(s).substring(z"0", s.size - extSize);
  }

}



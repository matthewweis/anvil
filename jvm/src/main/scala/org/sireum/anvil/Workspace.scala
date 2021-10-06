// #Sireum

package org.sireum.anvil

import org.sireum._
import org.sireum.anvil.Workspace.{file, mkdir}

object Workspace {

  def file(path: Os.Path): Os.Path = {
    assert(if (path.exists) path.isFile else T)
    return path
  }

  def mkdir(path: Os.Path): Os.Path = {
    if (path.exists) {
      assert(path.isDir)
    } else {
      path.mkdirAll()
    }
    return path
  }
}

@datatype class ProjectWorkspace(val root: Os.Path) {
  val downloads:          Os.Path = mkdir(root / "downloads")
  val sources:            Os.Path = mkdir(root / "sources")
  val original:           Os.Path = mkdir(sources / "original") // original (slang) sources before transpiling
  val transpiled:         Os.Path = mkdir(sources / "transpiled") // result of transpiling, needed for HLS to create drivers.
  val project:            Os.Path = mkdir(root / "project")
  val hls:                Os.Path = mkdir(project / "hls")
  val hw:                 Os.Path = mkdir(project / "hw")
  val sw:                 Os.Path = mkdir(project / "sw")
  val driverCalls:        Os.Path = mkdir(sw / "driver-calls")
  val modifiedTranspiled: Os.Path = mkdir(sw / "modified-transpiled")
  val os:                 Os.Path = mkdir(project / "os")
}

@datatype class InstallerWorkspace(val root: Os.Path) {
  val provision:                 Os.Path = mkdir(root / "provision")
  val downloads:                 Os.Path = mkdir(root / "downloads")
  val scripts:                   Os.Path = mkdir(provision / "scripts")
  val fixDashScript:             Os.Path = file(scripts / "fix_dash.sh")
  val installPetalinuxScript:    Os.Path = file(scripts / "install_petalinux.sh")
  val installVivadoScript:       Os.Path = file(scripts / "install_vivado.sh")
  val installDependenciesScript: Os.Path = file(scripts / "install_dependencies.sh")
  val installSireumScript:       Os.Path = file(scripts / "install_kekinian.sh")
}

@datatype class SandboxWorkspace(val local: Os.Path) {
  // when building in sandbox, use strings to represent paths in the vm
  val root:               ISZ[String] = ISZ("", "home", "vagrant")
  val project:            ISZ[String] = root ++ ISZ("project")
  val hls:                ISZ[String] = project ++ ISZ("hls")
  val hw:                 ISZ[String] = project ++ ISZ("hw")
  val sw:                 ISZ[String] = project ++ ISZ("sw")
  val os:                 ISZ[String] = project ++ ISZ("os")
}
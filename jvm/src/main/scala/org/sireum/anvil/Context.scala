// #Sireum

package org.sireum.anvil

import org.sireum._
import org.sireum.ops.StringOps

/**
* Contains all contextual values (values determined at runtime after parsing but but stage execution) and operations
* used in Anvil.
*
*     ApplicationContext = SandboxContext | CompileContext
*       SandboxInstallationContext // - A limited context used to create sandboxes. Allows full access to installer workspace.
*       CompileContext = ToolchainContext * HardwareContext * ExecutionContext
*         ToolchainContext // Conventions, defaults, and assumptions about Xilinx tools. For example: output file
*                             formats, version numbers, executables, etc.
*         HardwareContext  // Constants and derived values that vary by target hardware. For example: part_number,
*                             address_layouts, etc.
*         ExecutionContext = ProjectContext * (SandboxContext | Null) // Contains values that vary per-execution. Such
*                                                                        as arguments passed by the user or means to
*                                                                        determine which stages have already been run.
*           ProjectContext        // Names, variables, and settings which may vary between projects but not stages.
*                                    E.g. "bus name" "solution name" "top function" etc.
*           Option[SandboxContext]
*             None // Indicates that no SandboxContext was passed
*             Some[SandboxContext]
*/
object Context {

  @sig trait LocalContext {

    def runProc[T](path: Os.Path, proc: ISZ[String]): Os.Proc.Result = {
      val prefix: ISZ[String] = if (Os.kind == Os.Kind.Win) ISZ("cmd", "/c") else ISZ[String]()
      return Os.proc(prefix ++ proc).at(path).console.runCheck()
    }
  }

  @sig trait SandboxContextBase extends LocalContext {
    def port: String // ssh port
    def hostname: String
    def username: String
    def password: String

    def up(): Os.Proc.Result
    def localSandboxProc(proc: ISZ[String]): Os.Proc.Result
  }

  @sig trait SandboxInstallationContext extends SandboxContextBase {
    def workspace: InstallerWorkspace
    def installSireum: B
    def petalinuxInstallerPath: Option[Os.Path]
    def xilinxUnifiedPath: Option[Os.Path]

    /*
     * Location of petalinux source script relative to the installation folder.
     */
    def petalinuxSourceScriptRelativePath: ISZ[String]
    /*
     * Location of xilinx source script relative to the installation folder.
     */
    def xilinxUnifiedSourceScriptRelativePath: ISZ[String]

    /*
     * List of dependencies required by petalinux via apt-get. These are available in the user guide for each petalinux version.
     */
    def petalinuxDependencies: ISZ[String]

    def vmName(): String
    def numCPUs(): String
    def vramSize(): String
    def memorySizeMB(): String
    def enableGUI(): String
    def disksize(): String
    def graphicsController(): String

    override def localSandboxProc(proc: ISZ[String]): Os.Proc.Result = {
      return runProc(workspace.root, proc)
    }

    def up(): Os.Proc.Result = {
      return localSandboxProc(ISZ("vagrant", "up"))
    }
  }

  @sig trait CompileContext extends LocalContext {
    def toolchainContext: ToolchainContext
    def hardwareContext: HardwareContext
    def executionContext: ExecutionContext
  }

  @sig trait HardwareContext {

    def template_project_part_number: String

    // todo double-check we enforce this renamed bundle in hls!
    def template_project_bus: String = {
      return "AXILiteS"
    }
  }

  @sig trait ToolchainContext {

    // all functions which accept the entire workspace to be as flexible as possible for different tool versions
    def driverName(context: CompileContext): String
    def driverBaseFileName(context: CompileContext): String
    def versionedDriverName(context: CompileContext): String
    def hlsDriverImplDirectory(context: CompileContext): Os.Path
    // consider adding helper method "isValidNamingConvention" to check user input against Xilinx tools

    def runProc[T](proc: ISZ[String]): Os.Proc.Result = {
      if (Os.kind == Os.Kind.Win) {
        val windowsPrefix: ISZ[String] = ISZ("cmd", "/c")
        Os.proc(windowsPrefix ++ proc).console.run()
      } else {
        Os.proc(proc).console.run()
      }
    }
  }

  /**
  * Configured for the following toolchain:
  *   - Vivado Design Suite v2020.1
  *   - Petalinux v2020.1
  */
  @datatype class DefaultToolchainContext() extends ToolchainContext {

    override def driverName(context: CompileContext): String = {
      return context.executionContext.projectContext.template_project_hls_sources
    }

    override def driverBaseFileName(context: CompileContext): String = {
      return StringOps(driverName(context)).toLower
    }

    override def versionedDriverName(context: CompileContext): String = {
      return s"${driverName(context)}_v1_0"
    }

    override def hlsDriverImplDirectory(context: CompileContext): Os.Path = {
      val driverDirectory: String = versionedDriverName(context)
      val hlsSolutionName = context.executionContext.projectContext.template_project_hls_solution
      val projectWorkspace = context.executionContext.projectContext.projectWorkspace
      projectWorkspace.hls / hlsSolutionName / "impl" / "misc" / "drivers" / driverDirectory / "src";
    }
  }

  @sig trait ProjectContext {
    def projectWorkspace: ProjectWorkspace
    def apps: ISZ[String]
    def template_project_top_function: String
    def template_project_hls_solution: String
    def template_project_vivado_project: String
    def template_project_vivado_design: String
    def template_project_hls_sources: String
  }

  @enum object CompileStage {
    'Hls
    'Hw
    'Sw
    'Os
  }

  @sig trait ExecutionContext {
    def projectContext: ProjectContext
    def sandbox: Option[SandboxContext]
    def stages: Set[CompileStage.Type]
  }

  @enum object ScpDirection {
    'LocalToSandbox
    'SandboxToLocal
  }

  @sig trait SandboxContext extends SandboxContextBase {

    def workspace: SandboxWorkspace

    override def localSandboxProc(proc: ISZ[String]): Os.Proc.Result = {
      return runProc(workspace.local, proc)
    }

    def up(): Os.Proc.Result = {
      return localSandboxProc(ISZ("vagrant", "up", "--no-provision"))
    }

    def ssh[T](proc: ISZ[String]): Os.Proc.Result = {
      return localSandboxProc(ISZ("vagrant", "ssh", "-c", st"'${(proc, " ")}'".render))
    }

    def scp(dir: ScpDirection.Type, localPath: Os.Path, remotePath: ISZ[String]): Os.Proc.Result = {
      val tool: ISZ[String] = ISZ("scp")
      val fileFlag: ISZ[String] = if (localPath.isDir) ISZ("-r") else ISZ()
      val portFlag: ISZ[String] = ISZ("-P", port)
      val remote: String = st"$username@$hostname:${(for (file <- remotePath) yield st"$file", "/")}".render // should be /home/vagrant/project
      val local: String = localPath.string
      val files: ISZ[String] = dir match {
        case ScpDirection.LocalToSandbox => ISZ(local, remote)
        case ScpDirection.SandboxToLocal => ISZ(remote, local)
      }
      return localSandboxProc(tool ++ fileFlag ++ portFlag ++ files)
    }

    def clearProjectDir(remotePath: ISZ[String]): Os.Proc.Result = {
      val path: String = st"${(remotePath, "/")}".render
      val rm: ISZ[String] = ISZ("rm", "-rf")
      val and: ISZ[String] = ISZ("&&")
      val mkdir: ISZ[String] = ISZ("mkdir", "-p", path)
      return ssh(mkdir ++ and ++ rm ++ and ++ mkdir)
    }

    def push(localPath: Os.Path, remotePath: ISZ[String]): Os.Proc.Result = {
      return scp(ScpDirection.LocalToSandbox, localPath, remotePath)
    }

    def pull(localPath: Os.Path, remotePath: ISZ[String]): Os.Proc.Result = {
      return scp(ScpDirection.SandboxToLocal, localPath, remotePath)
    }
  }

  //
  // Built-in convenience contexts
  //

  @datatype class HardwareContext_Zynq_7000_SoC_ZedBoard() extends HardwareContext {
    // xc7z020clg484-1
    val template_project_part_number: String = {
      val zedFamily = "xc7z020"
      val zedPackage = "clg484"
      st"$zedFamily$zedPackage-1".render
    }
  }

  @datatype class SimpleProjectContext(val projectWorkspace: ProjectWorkspace,
                                       val simpleMethodName: String,
                                       val apps: ISZ[String]) extends ProjectContext {
    val template_project_top_function: String = simpleMethodName
    val template_project_hls_solution: String = "generatedSolution"
    val template_project_vivado_project: String = "generatedProject"
    val template_project_vivado_design: String = "generatedDesign"
    val template_project_hls_sources: String = simpleMethodName
  }

  @datatype class DefaultCompileContext(val hardwareContext: HardwareContext,
                                        val toolchainContext: ToolchainContext,
                                        val executionContext: ExecutionContext) extends CompileContext {}

  @sig trait SimpleSSH extends SandboxContextBase {

    override def port: String = {
      return "2222"
    }

    override def hostname: String = {
      return "anvil"
    }

    override def username: String = {
      return "vagrant"
    }

    override def password: String = {
      return "vagrant"
    }
  }

  @sig trait SimpleInstall extends SandboxInstallationContext {

    override def vmName(): String = {
      return "anvil"
    }

    override def numCPUs(): String = {
      return "4"
    }

    override def vramSize(): String = {
      return "64"
    }

    override def memorySizeMB(): String = {
      return "8192"
    }

    override def enableGUI(): String = {
      return "true"
    }

    override def graphicsController(): String = {
      if (Os.isWin) {
        return "vmsvga"
      } else {
        return "VBoxSVGA"
      }
    }

    override def disksize(): String = {
      val installPetalinux: B = petalinuxInstallerPath.nonEmpty
      val installXilinx: B = xilinxUnifiedPath.nonEmpty
      val toolsBOM: (B, B, B) = (installSireum, installPetalinux, installXilinx)

      // default extremely rough estimates. Should be part of config
      val gb: Z = toolsBOM match {
        // (sireum? petalinux? xilinx?) <---- tuple order
        case Tuple3(F, F, F) => 64  // CASE #1: environment + dependencies, but no tools preinstalled. sUse little and let users adjust if needed.
        case Tuple3(F, F, T) => 128 // CASE #2: big with huge installer
        case Tuple3(F, T, F) => 128 // CASE #3: smaller with potentially huge sstate cache
        case Tuple3(F, T, T) => 256 // CASE #4: (too small?) big installer + sstate + apps. Can probably lower if run then delete installer before petalinux install.
        case Tuple3(T, F, F) => 64  // CASE #5: sireum tools don't require too much memory, but hint that development may occur on the box.
        case Tuple3(T, F, T) => 128 // CASE #6:
        case Tuple3(T, T, F) => 128 // CASE #7:
        case Tuple3(T, T, T) => 256 // CASE #8: (too small?)
      }
      return st"${gb}GB".render
    }
  }

  @sig trait PetalinuxInstaller_v2020_1 extends SandboxInstallationContext {

    override def petalinuxSourceScriptRelativePath: ISZ[String] = {
      return ISZ("settings.sh")
    }

    /*
     * Derived from UG1144 (v2020.1), "Petalinux Tools Documentation", Table 2: Packages and Linux Workstation Environments
     * https://www.xilinx.com/support/documentation/sw_manuals/xilinx2020_1/ug1144-petalinux-tools-reference-guide.pdf#unique_26
     */
    override def petalinuxDependencies: ISZ[String] = {
      val official: ISZ[String] = ISZ[String](
        "iproute2", "gcc", "g++", "net-tools", "libncurses5-dev", "zlib1g:i386", "libssl-dev", "flex", "bison",
        "libselinux1", "xterm", "autoconf", "libtool", "texinfo", "zlib1g-dev", "gcc-multilib", "build-essential",
        "screen", "pax", "gawk", "python3", "python3-pexpect", "python3-pip", "python3-git", "python3-jinja2",
        "xz-utils", "debianutils", "iputils-ping", "libegl1-mesa", "libsdl1.2-dev", "pylint3", "cpio"
      )

      return official
    }
  }

  // temp
  def seqToSet[T](seq: ISZ[T]): Set[T] = {
    return Set.empty[T] ++ seq
  }

  @sig trait XilinxUnifiedInstaller_v2020_1 extends SandboxInstallationContext {
    override def xilinxUnifiedSourceScriptRelativePath: ISZ[String] = {
      return ISZ("Vivado", "2020.1", "settings64.sh")
    }
  }

  @datatype class SimpleSandboxContext(val workspace: SandboxWorkspace) extends SandboxContext with SimpleSSH {}

  @datatype class SimpleExecutionContext(val projectContext: ProjectContext,
                                         val sandbox: Option[SandboxContext],
                                         val stages: Set[CompileStage.Type]) extends ExecutionContext {}

  @datatype class SimpleSandboxInstallationContext_v2020_1(val workspace: InstallerWorkspace,
                                                           val installSireum: B,
                                                           val petalinuxInstallerPath: Option[Os.Path],
                                                           val xilinxUnifiedPath: Option[Os.Path])
    extends SandboxInstallationContext
      with SimpleSSH
      with SimpleInstall
      with PetalinuxInstaller_v2020_1
      with XilinxUnifiedInstaller_v2020_1 {}
}
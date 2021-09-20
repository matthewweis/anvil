// #Sireum
/*
 Copyright (c) 2021, Matthew Weis, Kansas State University
 All rights reserved.

 Redistribution and use in source and binary forms, with or without
 modification, are permitted provided that the following conditions are met:

 1. Redistributions of source code must retain the above copyright notice, this
    list of conditions and the following disclaimer.
 2. Redistributions in binary form must reproduce the above copyright notice,
    this list of conditions and the following disclaimer in the documentation
    and/or other materials provided with the distribution.

 THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND
 ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED
 WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
 DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT OWNER OR CONTRIBUTORS BE LIABLE FOR
 ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES
 (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES;
 LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND
 ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
 (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS
 SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */

package org.sireum.anvil

import org.sireum._
import org.sireum.cli.CliOpt.{Group, Opt, Tool, Type}

object cli {

  val anvilCompile: Tool = Tool(
    name = "anvilCompile",
    description = "Compile one or more stages",
    header = "Compile one or more stages",
    usage = "<option>* ( <slang-file> )* <slang-file#method-to-accel>",
    command = "compile",
    opts = ISZ(
      Opt(
        name = "stage",
        longKey = "stage",
        shortKey = None(),
        tpe = Type.Choice("stage", Some(','), ISZ("all","hls","hw","sw","os")),
        description = "Run the selected stages. Note that \"all\" is just shortcut for \"hls,hw,sw,os\"."
      ),
      Opt(
        name = "transpilerArgs",
        longKey = "transpiler-args-file",
        shortKey = None(),
        tpe = Type.Path(multiple = F, default = None()),
        description = st"[${(ISZ("File containing args to be forwarded to the transpiler.",
          "Anvil will intercept the transpiler's \"--output\" flag and use it to create a workspace.",
          "Each flag/value should be on its own line. For example:\n",
          "--sourcepath\npath/to/src\n--name\nmy_project\n--stable-type-id\n--unroll\n...etc"), "")}]".render
      ),
      Opt(
        name = "sandboxPath",
        longKey = "sandbox-path",
        shortKey = None(),
        tpe = Type.Path(multiple = F, default = None()),
        description = st"[${(ISZ("Optional path to a sandbox that execution will be delegated to.",
          "Type \"anvil sandbox help\" for more info."), "")}]".render
      )
    ),
    groups = ISZ(),
    usageDescOpt = None()
  )

  val anvilSandbox: Tool = Tool(
    name = "anvilSandbox",
    description = "Create a premade anvil execution environment.",
    header = "Create a linux sandbox that may optionally be hooked into Anvil or used as a debugging workspace.",
    usage = "\"sandbox\" (args)* <output-path>",
    command = "sandbox",
    opts = ISZ(
      Opt(
        name = "excludeSireum",
        longKey = "exclude-sireum",
        shortKey = Some('s'),
        tpe = Type.Flag(F),
        // By including Sireum, Anvil can sandbox its "hls#transpiler_pass" and "sw" stages.
        // This is never necessary but can be very handy when working or debugging inside a sandbox.
        description = st"[${(ISZ("Indicates that Sireum should NOT be included in the sandbox.",
          "Sireum enables sandboxing for Anvil's \"hls#transpiler_pass\" and \"sw\" compilation stages.",
          "This flag only exists for convenience."), "")}]".render
      ),
      Opt(
        name = "xilinxUnifiedPath",
        longKey = "xilinx-unified-path",
        shortKey = Some('x'),
        tpe = Type.Path(multiple = F, default = None()),
        // By using this flag, Anvil will automatically install Vivado and Vivado HLS tools into the sandbox.
        // This means Anvil's "hw" and "hls" compilation stages can (optionally) run in the environment.
        // Sandboxing is useful for users working on an unsupported OS. A list of supported operating systems can be
        // found in Vivado's official release notes ("Supported Operating Systems" page 8):
        //    - "Vivado Design Suite 2020.1 User Guide UG973 (v2020.1)#Supported Operating Systems"
        //    - https://www.xilinx.com/support/documentation/sw_manuals/xilinx2020_1/ug973-vivado-release-notes-install-license.pdf#_OPENTOPIC_TOC_PROCESSING_d99e2007
        //
        // Anvil cannot automatically download this file because a (free) Xilinx account is required to gain access.
        // Users can manually download the file ("Xilinx Vitis 2020.1: All OS installer Single-File Download")
        // via direct link: https://www.xilinx.com/member/forms/download/xef.html?filename=Xilinx_Unified_2020.1_0602_1208.tar.gz
        // or via the downloads page: https://www.xilinx.com/support/download/index.html/content/xilinx/en/downloadNav/vitis/archive-vitis.html
        //
        // Anvil installs the license-free ("WebPACK") edition of the tools. Users can upgrade to the full version
        // via the pre-installed "Vivado License Manager" application inside the sandbox. Note that the ZedBoard
        // is supported by WebPACK and does not need an upgrade. A list of supported devices can be found in
        // the official release notes linked below ("Supported Devices" page 8):
        //     - Vivado Design Suite 2020.1 User Guide UG973 (v2020.1)#Supported Devices
        //     - https://www.xilinx.com/support/documentation/sw_manuals/xilinx2020_1/ug973-vivado-release-notes-install-license.pdf#_OPENTOPIC_TOC_PROCESSING_d99e2072
        description = st"[${(ISZ("Path to Xilinx_Unified_2020.1_0602_1208.tar.gz. Enables sandboxing for Anvil's \"hls#vivado_hls\" and \"hw\" compilation stages.",
          "Download from https://www.xilinx.com/member/forms/download/xef.html?filename=Xilinx_Unified_2020.1_0602_1208.tar.gz (login required)"), "")}]".render
      ),
      Opt(
        name = "petalinuxInstallerPath",
        longKey = "petalinux-installer-path",
        shortKey = Some('p'),
        tpe = Type.Path(multiple = F, default = None()),
        // By including this file, Anvil will automatically install Petalinux into the sandbox.
        // This enables execution-sandboxing for Anvil's "os" compilation stage. Sandboxing is useful
        // for users working on an unsupported OS. A list of supported operating systems can be found in Petalinux's
        // official release notes ("Installation Steps" subsection "Installation Requirements" page 9):
        //    - "Petalinux Tools Documentation UG1144 (v2020.1)#Installation Requirements"
        //    - https://www.xilinx.com/support/documentation/sw_manuals/xilinx2020_1/ug1144-petalinux-tools-reference-guide.pdf#_OPENTOPIC_TOC_PROCESSING_d99e5069
        //
        // Anvil cannot automatically download this file because a (free) Xilinx account is required to gain access.
        // Users can manually download the file ("petalinux-v2020.1-final-installer.run")
        // via direct link: https://www.xilinx.com/member/forms/download/xef.html?filename=petalinux-v2020.1-final-installer.run
        // or via the downloads page: https://www.xilinx.com/support/download/index.html/content/xilinx/en/downloadNav/embedded-design-tools/archive.html
        description = st"[${(ISZ("Path to petalinux-v2020.1-final-installer.run. Enables sandboxing for Anvil's \"os\" compilation stages.",
          "Download from https://www.xilinx.com/member/forms/download/xef.html?filename=petalinux-v2020.1-final-installer.run (login required)"), "")}]".render
      ),
    ),
    groups = ISZ(),
    usageDescOpt = None()
  )

  val group: Group = Group(
    name = "anvil",
    description = "Anvil tool",
    header = "Sireum Anvil",
    unlisted = F,
    subs = ISZ(anvilCompile, anvilSandbox)
  )

}
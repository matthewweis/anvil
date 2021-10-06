// #Sireum

package org.sireum.anvil

import org.sireum._
import org.sireum.anvil.Context.CompileContext

object AnvilCompiler {

  @sig trait Stage {
    def workspace: ProjectWorkspace
    def folder: Os.Path
    def setup(workspace: ProjectWorkspace): Z
    def local(workspace: ProjectWorkspace): Z
  }

  def compile(context: CompileContext): Z = {
    // COMPILER LINK
    return z"-1"
  }

}



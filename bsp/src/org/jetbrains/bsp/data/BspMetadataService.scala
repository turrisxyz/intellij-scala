package org.jetbrains.bsp.data

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.externalSystem.model.DataNode
import com.intellij.openapi.externalSystem.model.project.ProjectData
import com.intellij.openapi.externalSystem.service.project.IdeModifiableModelsProvider
import com.intellij.openapi.module.Module
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.{LanguageLevelModuleExtensionImpl, ModifiableRootModel, ModuleRootManager}
import com.intellij.pom.java.LanguageLevel
import org.jetbrains.bsp.BspUtil._
import org.jetbrains.plugins.scala.project.external.{ScalaAbstractProjectDataService, JdkByHome, JdkByVersion, SdkUtils}

import java.util

class BspMetadataService extends ScalaAbstractProjectDataService[BspMetadata, Module](BspMetadata.Key) {

  override def importData(
    toImport: util.Collection[_ <: DataNode[BspMetadata]],
    projectData: ProjectData,
    project: Project,
    modelsProvider: IdeModifiableModelsProvider
  ): Unit = {
    toImport.forEach { node =>
      doImport(node)(modelsProvider)
    }
  }

  private def doImport(node: DataNode[BspMetadata])(implicit modelsProvider: IdeModifiableModelsProvider) = {
    modelsProvider.getIdeModuleByNode(node).map { module =>
      val data = node.getData
      val jdkByHome = Option(data.javaHome).map(_.toFile).map(JdkByHome)
      val jdkByVersion = Option(data.javaVersion).map(JdkByVersion)
      val existingJdk = Option(ModuleRootManager.getInstance(module).getSdk)
      val moduleJdk = jdkByHome
        .orElse(jdkByVersion)
        .flatMap(SdkUtils.findProjectSdk)
        .orElse(existingJdk)

      val model = modelsProvider.getModifiableRootModel(module)
      model.inheritSdk()
      moduleJdk.foreach(model.setSdk)

      val moduleJdkVersion = moduleJdk.map(_.getVersionString)

      Option(data.languageLevel)
        .orElse(moduleJdkVersion.map(LanguageLevel.parse))
        .flatMap(versionString => Option(versionString))
        .foreach(setLanguageLevel(model, _))
    }
  }

  private def setLanguageLevel(model: ModifiableRootModel, languageLevel: LanguageLevel): Unit = {
    ApplicationManager.getApplication.invokeLater(() => {
      ApplicationManager.getApplication.runWriteAction(new Runnable {
        override def run(): Unit = {
          val languageLevelExtesion = model.getModuleExtension(classOf[LanguageLevelModuleExtensionImpl])
          languageLevelExtesion.setLanguageLevel(languageLevel)
        }
      })
    })
  }
}

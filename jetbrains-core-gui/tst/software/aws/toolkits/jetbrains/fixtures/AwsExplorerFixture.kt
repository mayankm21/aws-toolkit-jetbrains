// Copyright 2020 Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0

package software.aws.toolkits.jetbrains.fixtures

import com.intellij.openapi.project.Project
import com.intellij.testGuiFramework.fixtures.IdeFrameFixture
import com.intellij.testGuiFramework.fixtures.ToolWindowFixture
import com.intellij.testGuiFramework.fixtures.extended.ExtendedJTreePathFixture
import com.intellij.testGuiFramework.framework.GuiTestUtil
import com.intellij.testGuiFramework.framework.Timeouts
import com.intellij.testGuiFramework.impl.GuiTestUtilKt
import com.intellij.testGuiFramework.util.step
import com.intellij.ui.treeStructure.Tree
import com.intellij.util.ui.AsyncProcessIcon
import org.fest.swing.core.Robot
import org.fest.swing.fixture.JTreeFixture
import org.fest.swing.timing.Pause

class AwsExplorerFixture(project: Project, robot: Robot) : ToolWindowFixture("aws.explorer", project, robot) {
    override fun activate() {
        super.activate()
        waitUntilIsVisible()
    }

    fun explorerTree(): JTreeFixture {
        val tree = getTree()

        waitForTreeToBeLoaded(tree)

        return JTreeFixture(myRobot, tree)
    }

    fun explorerTree(node: String, vararg  paths: String): ExtendedJTreePathFixture {
        val tree = getTree()

        waitForTreeToBeLoaded(tree)

        return ExtendedJTreePathFixture(tree, listOf(node) + paths, robot = myRobot)
    }

    fun waitForTreeToBeLoaded(tree: Tree = getTree()) {
        println("waiting for tree to be loaded")
        Pause.pause(object : org.fest.swing.timing.Condition("wait for the explorer tree to be fully loaded") {
            override fun test(): Boolean {
                val isNotEmpty = GuiTestUtilKt.computeOnEdt {
                    !tree.isEmpty
                }

                println("yay? ${isNotEmpty == true}")

                return isNotEmpty == true
            }
        }, Timeouts.defaultTimeout)



        GuiTestUtil.waitUntilGone(tree, GuiTestUtilKt.typeMatcher(AsyncProcessIcon::class.java) { !it.isShowing })

        println("tree is loaded")
    }

    private fun getTree(): Tree {
        return GuiTestUtil.waitUntilFound(myRobot, myToolWindow.component.parent, GuiTestUtilKt.typeMatcher(Tree::class.java) { it.isShowing })
    }
}

fun IdeFrameFixture.awsExplorer(): AwsExplorerFixture = AwsExplorerFixture(this.project, this.robot())

fun IdeFrameFixture.awsExplorer(func: AwsExplorerFixture.() -> Unit) {
    step("at AWS Explorer") {
        func(this.awsExplorer())
    }
}

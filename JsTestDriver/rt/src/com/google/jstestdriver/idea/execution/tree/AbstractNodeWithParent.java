package com.google.jstestdriver.idea.execution.tree;

import org.jetbrains.annotations.NotNull;

/**
 * @author Sergey Simonchik
 */
public class AbstractNodeWithParent<T extends AbstractNodeWithParent> extends AbstractNode<T> {

  private final AbstractNode myParent;
  private final String myName;

  public AbstractNodeWithParent(@NotNull String name, @NotNull AbstractNode parent) {
    super(parent.getTreeManager());
    myName = name;
    myParent = parent;
  }

  @NotNull
  public String getName() {
    return myName;
  }

  @NotNull
  public AbstractNode getParent() {
    return myParent;
  }
}
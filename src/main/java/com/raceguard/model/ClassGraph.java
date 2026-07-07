package com.raceguard.model;

import java.util.ArrayList;
import java.util.List;

public class ClassGraph {

    public String className;

    public String sourceFile;

    public List<FieldInfo> fields = new ArrayList<>();

    public List<MethodInfo> methods = new ArrayList<>();

}
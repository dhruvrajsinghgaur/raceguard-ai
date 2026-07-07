package com.raceguard.model;

import java.util.ArrayList;
import java.util.List;

public class ProjectGraph {

    public List<ClassGraph> classes = new ArrayList<>();

    public List<CrossClassAccess> crossClassAccesses = new ArrayList<>();

    public List<ProjectRisk> projectRisks = new ArrayList<>();

}
import React, {createContext, useContext, useMemo, useState} from 'react';
import type {Project} from '../types/project';

type ProjectStoreValue = {
  projects: Project[];
  createProject: () => Project;
};

const ProjectStoreContext = createContext<ProjectStoreValue | null>(null);

const starterProject: Project = {
  id: 'starter-project',
  title: 'Launch Reel',
  durationMs: 12400,
  updatedAt: Date.now() - 1000 * 60 * 42,
  aspectRatio: '9:16',
};

export function ProjectStoreProvider({children}: {children: React.ReactNode}) {
  const [projects, setProjects] = useState<Project[]>([starterProject]);

  const value = useMemo<ProjectStoreValue>(() => ({
    projects,
    createProject: () => {
      const project: Project = {
        id: `project-${Date.now()}`,
        title: `Untitled ${projects.length + 1}`,
        durationMs: 10000,
        updatedAt: Date.now(),
        aspectRatio: '9:16',
      };
      setProjects(current => [project, ...current]);
      return project;
    },
  }), [projects]);

  return (
    <ProjectStoreContext.Provider value={value}>
      {children}
    </ProjectStoreContext.Provider>
  );
}

export function useProjectStore() {
  const value = useContext(ProjectStoreContext);
  if (!value) {
    throw new Error('useProjectStore must be used inside ProjectStoreProvider');
  }
  return value;
}

import React from 'react';
import {Pressable, ScrollView, StyleSheet, Text, View} from 'react-native';
import {SafeAreaView} from 'react-native-safe-area-context';
import type {NativeStackScreenProps} from '@react-navigation/native-stack';
import {ProjectCard} from '../components/ProjectCard';
import {useProjectStore} from '../store/ProjectStore';
import {colors, spacing} from '../theme/tokens';
import type {RootStackParamList} from '../navigation/types';

type Props = NativeStackScreenProps<RootStackParamList, 'Projects'>;

export function ProjectsScreen({navigation}: Props) {
  const {projects} = useProjectStore();
  return (
    <SafeAreaView style={styles.safe} edges={['top']}>
      <View style={styles.header}>
        <Pressable onPress={() => navigation.goBack()} hitSlop={12}><Text style={styles.back}>‹</Text></Pressable>
        <Text style={styles.title}>Projects</Text>
        <View style={styles.headerSpacer} />
      </View>
      <ScrollView contentContainerStyle={styles.content}>
        <Text style={styles.count}>{projects.length} local project{projects.length === 1 ? '' : 's'}</Text>
        <View style={styles.list}>
          {projects.map(project => (
            <ProjectCard key={project.id} project={project} onPress={() => navigation.navigate('Editor', {projectId: project.id})} />
          ))}
        </View>
      </ScrollView>
    </SafeAreaView>
  );
}

const styles = StyleSheet.create({
  safe: {flex: 1, backgroundColor: colors.background},
  header: {height: 58, flexDirection: 'row', alignItems: 'center', justifyContent: 'space-between', paddingHorizontal: spacing.lg, borderBottomWidth: 1, borderBottomColor: colors.border},
  back: {color: colors.text, fontSize: 36, lineHeight: 36},
  title: {color: colors.text, fontSize: 17, fontWeight: '800'},
  headerSpacer: {width: 28},
  content: {padding: spacing.lg, paddingBottom: 40},
  count: {color: colors.textMuted, fontSize: 12, marginBottom: spacing.md},
  list: {gap: spacing.sm},
});

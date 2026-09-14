import React from 'react';
import {ScrollView, StyleSheet, Text, View, Pressable} from 'react-native';
import {SafeAreaView} from 'react-native-safe-area-context';
import type {NativeStackScreenProps} from '@react-navigation/native-stack';
import {PrimaryButton} from '../components/PrimaryButton';
import {ProjectCard} from '../components/ProjectCard';
import {useProjectStore} from '../store/ProjectStore';
import {colors, radius, spacing} from '../theme/tokens';
import type {RootStackParamList} from '../navigation/types';

type Props = NativeStackScreenProps<RootStackParamList, 'Home'>;

const chips = ['Multi-track', 'Keyframes', 'AI-ready'];

export function HomeScreen({navigation}: Props) {
  const {projects, createProject} = useProjectStore();

  const startProject = () => {
    const project = createProject();
    navigation.navigate('Editor', {projectId: project.id});
  };

  return (
    <SafeAreaView style={styles.safe} edges={['top']}>
      <ScrollView contentContainerStyle={styles.content} showsVerticalScrollIndicator={false}>
        <View style={styles.header}>
          <View>
            <Text style={styles.eyebrow}>VEC STUDIO</Text>
            <Text style={styles.title}>Create without limits.</Text>
          </View>
          <View style={styles.avatar}><Text style={styles.avatarText}>V</Text></View>
        </View>

        <View style={styles.hero}>
          <View style={styles.orbOne} />
          <View style={styles.orbTwo} />
          <Text style={styles.heroOverline}>MOBILE VIDEO EDITOR</Text>
          <Text style={styles.heroTitle}>Fast enough for ideas. Built for serious edits.</Text>
          <Text style={styles.heroBody}>Patch 01 establishes the product shell that the native editing engine will plug into.</Text>
          <View style={styles.chipRow}>
            {chips.map(chip => <View key={chip} style={styles.chip}><Text style={styles.chipText}>{chip}</Text></View>)}
          </View>
        </View>

        <PrimaryButton label="New project" hint="Open the editor workspace" onPress={startProject} />

        <View style={styles.sectionHeader}>
          <Text style={styles.sectionTitle}>Recent projects</Text>
          <Pressable onPress={() => navigation.navigate('Projects')}>
            <Text style={styles.link}>View all</Text>
          </Pressable>
        </View>

        <View style={styles.projectList}>
          {projects.slice(0, 2).map(project => (
            <ProjectCard
              key={project.id}
              project={project}
              onPress={() => navigation.navigate('Editor', {projectId: project.id})}
            />
          ))}
        </View>

        <View style={styles.foundationCard}>
          <Text style={styles.foundationKicker}>FOUNDATION STATUS</Text>
          <Text style={styles.foundationTitle}>Editor shell ready</Text>
          <Text style={styles.foundationBody}>Navigation, project state, design tokens, timeline shell and responsive editor layout are separated into reusable modules.</Text>
        </View>
      </ScrollView>
    </SafeAreaView>
  );
}

const styles = StyleSheet.create({
  safe: {flex: 1, backgroundColor: colors.background},
  content: {paddingHorizontal: spacing.lg, paddingBottom: 44},
  header: {flexDirection: 'row', alignItems: 'center', justifyContent: 'space-between', paddingTop: spacing.md, marginBottom: spacing.xl},
  eyebrow: {color: colors.accent, fontSize: 11, fontWeight: '900', letterSpacing: 1.8},
  title: {color: colors.text, fontSize: 28, lineHeight: 34, fontWeight: '900', marginTop: 5},
  avatar: {width: 42, height: 42, borderRadius: 21, alignItems: 'center', justifyContent: 'center', backgroundColor: colors.surfaceRaised, borderWidth: 1, borderColor: colors.border},
  avatarText: {color: colors.text, fontWeight: '900'},
  hero: {minHeight: 214, overflow: 'hidden', borderRadius: radius.xl, padding: spacing.xl, backgroundColor: colors.surface, borderWidth: 1, borderColor: colors.border, marginBottom: spacing.lg},
  orbOne: {position: 'absolute', width: 180, height: 180, borderRadius: 90, backgroundColor: '#5039B2', opacity: 0.42, right: -58, top: -66},
  orbTwo: {position: 'absolute', width: 120, height: 120, borderRadius: 60, backgroundColor: '#246D7B', opacity: 0.22, left: -42, bottom: -54},
  heroOverline: {color: colors.textMuted, fontSize: 10, fontWeight: '800', letterSpacing: 1.5},
  heroTitle: {color: colors.text, fontSize: 24, lineHeight: 30, fontWeight: '900', maxWidth: 290, marginTop: spacing.md},
  heroBody: {color: colors.textMuted, fontSize: 13, lineHeight: 19, maxWidth: 320, marginTop: spacing.sm},
  chipRow: {flexDirection: 'row', flexWrap: 'wrap', gap: 7, marginTop: spacing.lg},
  chip: {paddingHorizontal: 10, paddingVertical: 6, borderRadius: radius.pill, backgroundColor: 'rgba(255,255,255,0.07)'},
  chipText: {color: colors.text, fontSize: 11, fontWeight: '700'},
  sectionHeader: {flexDirection: 'row', justifyContent: 'space-between', alignItems: 'center', marginTop: spacing.xxl, marginBottom: spacing.md},
  sectionTitle: {color: colors.text, fontSize: 17, fontWeight: '800'},
  link: {color: colors.accent, fontSize: 13, fontWeight: '800'},
  projectList: {gap: spacing.sm},
  foundationCard: {marginTop: spacing.xxl, padding: spacing.lg, borderRadius: radius.lg, backgroundColor: colors.surfaceRaised},
  foundationKicker: {color: colors.success, fontSize: 10, fontWeight: '900', letterSpacing: 1.4},
  foundationTitle: {color: colors.text, fontSize: 16, fontWeight: '800', marginTop: 7},
  foundationBody: {color: colors.textMuted, fontSize: 12, lineHeight: 18, marginTop: 6},
});

import React from 'react';
import {Pressable, StyleSheet, Text, View} from 'react-native';
import type {Project} from '../types/project';
import {colors, radius, spacing} from '../theme/tokens';

function formatDuration(ms: number) {
  const total = Math.max(0, Math.round(ms / 1000));
  const minutes = Math.floor(total / 60);
  const seconds = String(total % 60).padStart(2, '0');
  return `${minutes}:${seconds}`;
}

export function ProjectCard({project, onPress}: {project: Project; onPress: () => void}) {
  return (
    <Pressable onPress={onPress} style={({pressed}) => [styles.root, pressed && styles.pressed]}>
      <View style={styles.thumbnail}>
        <View style={styles.thumbnailGlow} />
        <Text style={styles.thumbnailText}>{project.aspectRatio}</Text>
        <View style={styles.durationBadge}>
          <Text style={styles.duration}>{formatDuration(project.durationMs)}</Text>
        </View>
      </View>
      <View style={styles.meta}>
        <Text numberOfLines={1} style={styles.title}>{project.title}</Text>
        <Text style={styles.subtitle}>Local project · tap to edit</Text>
      </View>
      <Text style={styles.more}>•••</Text>
    </Pressable>
  );
}

const styles = StyleSheet.create({
  root: {
    flexDirection: 'row',
    alignItems: 'center',
    padding: spacing.md,
    borderRadius: radius.lg,
    backgroundColor: colors.surface,
    borderWidth: 1,
    borderColor: colors.border,
  },
  pressed: {opacity: 0.82},
  thumbnail: {
    width: 86,
    height: 60,
    borderRadius: radius.md,
    overflow: 'hidden',
    justifyContent: 'center',
    alignItems: 'center',
    backgroundColor: '#25203A',
  },
  thumbnailGlow: {
    position: 'absolute',
    width: 72,
    height: 72,
    borderRadius: 36,
    backgroundColor: '#4E35A8',
    opacity: 0.6,
    right: -20,
    top: -24,
  },
  thumbnailText: {color: colors.text, fontWeight: '800', fontSize: 13},
  durationBadge: {
    position: 'absolute',
    right: 6,
    bottom: 6,
    paddingHorizontal: 6,
    paddingVertical: 3,
    borderRadius: 6,
    backgroundColor: 'rgba(0,0,0,0.66)',
  },
  duration: {color: colors.white, fontSize: 10, fontWeight: '700'},
  meta: {flex: 1, marginLeft: spacing.md},
  title: {color: colors.text, fontSize: 15, fontWeight: '700'},
  subtitle: {color: colors.textMuted, fontSize: 12, marginTop: 5},
  more: {color: colors.textMuted, fontSize: 14, letterSpacing: 1},
});

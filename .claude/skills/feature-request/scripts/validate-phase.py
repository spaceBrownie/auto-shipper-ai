#!/usr/bin/env python3
"""
Feature Request Phase Validation Script

Provides deterministic validation of actions against phase permissions.
Must be run before any read/write/bash action in the feature request workflow.

Usage:
    python validate-phase.py --phase 1 --action info
    python validate-phase.py --phase 2 --action read --path "src/Foo.java"
    python validate-phase.py --phase 3 --action write --path "feature-requests/FR-001/spec.md"
    python validate-phase.py --phase 4 --action bash --command "git commit"
    python validate-phase.py --feature-name "jwt-refresh-tokens"
    python validate-phase.py --phase 2 --check-deliverables --feature-dir "feature-requests/FR-001"
"""

import argparse
import json
import os
import re
import sys
from pathlib import Path
from typing import Dict, List, Optional, Tuple
import yaml


class PhaseValidator:
    """Validates actions against feature request workflow phases"""

    def __init__(self, workflow_config_path: str):
        """Initialize validator with workflow configuration"""
        with open(workflow_config_path, 'r') as f:
            self.config = yaml.safe_load(f)

        self.global_config = self.config.get('global', {})
        self.phases = self.config.get('phases', {})
        self.validation_rules = self.config.get('validation', {})
        self.error_messages = self.config.get('error_messages', {})

    def get_phase_key(self, phase_number: int) -> str:
        """Convert phase number to phase key"""
        phase_map = {
            1: 'phase_1_discovery',
            2: 'phase_2_specification',
            3: 'phase_3_planning',
            4: 'phase_4_implementation'
        }
        return phase_map.get(phase_number)

    def get_phase_info(self, phase_number: int) -> Optional[Dict]:
        """Get complete phase configuration"""
        phase_key = self.get_phase_key(phase_number)
        return self.phases.get(phase_key) if phase_key else None

    def validate_feature_name(self, name: str) -> Tuple[bool, str]:
        """Validate feature name against naming rules"""
        rules = self.validation_rules.get('feature_name', {})
        pattern = rules.get('pattern', self.global_config.get('feature_name_pattern'))
        min_length = rules.get('min_length', 3)
        max_length = rules.get('max_length', self.global_config.get('max_feature_name_length', 50))
        forbidden_chars = rules.get('forbidden_chars', [])

        # Check length
        if len(name) < min_length:
            return False, f"Feature name too short (min {min_length} chars)"

        if len(name) > max_length:
            return False, f"Feature name too long (max {max_length} chars)"

        # Check pattern
        if pattern and not re.match(pattern, name):
            return False, self.error_messages.get('invalid_feature_name',
                                                   f"Feature name must match pattern: {pattern}")

        # Check forbidden characters
        for char in forbidden_chars:
            if char in name:
                return False, f"Feature name cannot contain: {char}"

        return True, "Valid feature name"

    def _match_glob_pattern(self, path: str, patterns: List[str]) -> bool:
        """Check if path matches any glob pattern"""
        import fnmatch

        for pattern in patterns:
            # Handle ** for recursive directory matching
            if '**' in pattern:
                # Convert glob pattern to regex
                # Split pattern by ** to handle each part
                parts = pattern.split('**')
                if len(parts) == 2:
                    # Pattern like "src/**/*.java" or "**/*.java"
                    prefix = parts[0].rstrip('/')
                    suffix = parts[1].lstrip('/')

                    # Check if path starts with prefix (if any) and ends matching suffix
                    if prefix and not path.startswith(prefix):
                        continue

                    # Check if the remaining path matches the suffix pattern
                    if prefix:
                        remaining = path[len(prefix):].lstrip('/')
                    else:
                        remaining = path

                    if suffix:
                        # Use fnmatch on the suffix pattern against any part of remaining path
                        # Try matching from any position in the remaining path
                        path_parts = remaining.split('/')
                        for i in range(len(path_parts)):
                            test_path = '/'.join(path_parts[i:])
                            if fnmatch.fnmatch(test_path, suffix):
                                return True
                    else:
                        # Pattern ends with **, matches anything after prefix
                        return True
                else:
                    # Multiple ** or complex pattern, try simple approach
                    regex_pattern = re.escape(pattern)
                    regex_pattern = regex_pattern.replace(r'\*\*', '.*')
                    regex_pattern = regex_pattern.replace(r'\*', '[^/]*')
                    regex_pattern = regex_pattern.replace(r'\?', '[^/]')
                    if re.match(f"^{regex_pattern}$", path):
                        return True
            else:
                # Use standard fnmatch for simple patterns
                if fnmatch.fnmatch(path, pattern):
                    return True

        return False

    def validate_read(self, phase_number: int, file_path: str) -> Tuple[bool, str]:
        """Validate if reading a file is allowed in the given phase"""
        phase_info = self.get_phase_info(phase_number)
        if not phase_info:
            return False, f"Invalid phase: {phase_number}"

        permissions = phase_info.get('permissions', {})
        read_patterns = permissions.get('read', [])

        # Empty list means no read permissions
        if not read_patterns:
            return False, self.error_messages.get('permission_denied_read',
                                                   f"Phase {phase_number} does not allow reading: {file_path}").format(
                phase=phase_number, path=file_path)

        # Check if path matches any allowed pattern
        if self._match_glob_pattern(file_path, read_patterns):
            return True, "Read permission granted"

        return False, f"Phase {phase_number} does not allow reading: {file_path}"

    def validate_write(self, phase_number: int, file_path: str) -> Tuple[bool, str]:
        """Validate if writing a file is allowed in the given phase"""
        phase_info = self.get_phase_info(phase_number)
        if not phase_info:
            return False, f"Invalid phase: {phase_number}"

        permissions = phase_info.get('permissions', {})
        write_patterns = permissions.get('write', [])

        # Empty list means no write permissions
        if not write_patterns:
            return False, self.error_messages.get('permission_denied_write',
                                                   f"Phase {phase_number} does not allow writing: {file_path}").format(
                phase=phase_number, path=file_path)

        # Check if path matches any allowed pattern
        if self._match_glob_pattern(file_path, write_patterns):
            return True, "Write permission granted"

        return False, f"Phase {phase_number} does not allow writing: {file_path}"

    def validate_bash_command(self, phase_number: int, command: str) -> Tuple[bool, str]:
        """Validate if bash command is allowed in the given phase"""
        phase_info = self.get_phase_info(phase_number)
        if not phase_info:
            return False, f"Invalid phase: {phase_number}"

        permissions = phase_info.get('permissions', {})
        bash_config = permissions.get('bash', {})
        allowed_commands = bash_config.get('allowed_commands', [])
        forbidden_commands = bash_config.get('forbidden_commands', [])

        # Extract base command (first word)
        base_command = command.strip().split()[0] if command.strip() else ""

        # Check if command is explicitly forbidden
        for forbidden in forbidden_commands:
            if command.startswith(forbidden) or base_command == forbidden:
                return False, f"Phase {phase_number} forbids command: {forbidden}"

        # Check if command is in allowed list
        for allowed in allowed_commands:
            if command.startswith(allowed) or base_command == allowed:
                return True, "Bash command allowed"

        # If not in allowed list and not forbidden, deny by default
        return False, f"Phase {phase_number} does not allow command: {base_command}"

    def check_deliverables(self, phase_number: int, feature_dir: str) -> Tuple[bool, List[str]]:
        """Check if all required deliverables exist for the phase"""
        phase_info = self.get_phase_info(phase_number)
        if not phase_info:
            return False, [f"Invalid phase: {phase_number}"]

        deliverables = phase_info.get('deliverables', [])
        errors = []

        for deliverable in deliverables:
            deliverable_type = deliverable.get('type')

            if deliverable_type == 'file':
                file_name = deliverable.get('name')
                file_path = os.path.join(feature_dir, file_name)

                # Check if file exists
                if not os.path.exists(file_path):
                    errors.append(f"Missing required deliverable: {file_name}")
                    continue

                # Check required sections if specified
                required_sections = deliverable.get('required_sections', [])
                if required_sections:
                    with open(file_path, 'r') as f:
                        content = f.read()

                    for section in required_sections:
                        # Look for markdown headers with this section name
                        # Use {{1,3}} to escape the braces in f-string, or use concatenation
                        pattern = r'^#{1,3}\s+' + re.escape(section)
                        if not re.search(pattern, content, re.MULTILINE):
                            errors.append(f"{file_name} missing required section: {section}")

                # Check forbidden sections if specified
                forbidden_sections = deliverable.get('forbidden_sections', [])
                if forbidden_sections:
                    with open(file_path, 'r') as f:
                        content = f.read()

                    for section in forbidden_sections:
                        pattern = r'^#{1,3}\s+' + re.escape(section)
                        if re.search(pattern, content, re.MULTILINE):
                            errors.append(f"{file_name} contains forbidden section: {section}")

                # Check required elements (like checkboxes)
                required_elements = deliverable.get('required_elements', [])
                if required_elements:
                    with open(file_path, 'r') as f:
                        content = f.read()

                    for element in required_elements:
                        if 'checkbox' in element.lower():
                            if not re.search(r'- \[(x| )\]', content):
                                errors.append(f"{file_name} missing required element: {element}")

                # Phase 4 specific: check all checkboxes are complete
                if phase_number == 4 and file_name == 'implementation-plan.md':
                    with open(file_path, 'r') as f:
                        content = f.read()

                    unchecked = re.findall(r'- \[ \]', content)
                    if unchecked:
                        errors.append(
                            f"implementation-plan.md has {len(unchecked)} unchecked tasks (all must be checked in phase 4)")

        # Phase 4 specific: check exactly 1 summary.md exists
        if phase_number == 4:
            required_summary_count = self.global_config.get('required_summary_count', 1)
            summary_path = os.path.join(feature_dir, 'summary.md')

            if not os.path.exists(summary_path):
                errors.append("Missing required summary.md")
            else:
                # Verify it's the only summary file
                summary_files = [f for f in os.listdir(feature_dir) if 'summary' in f.lower() and f.endswith('.md')]
                if len(summary_files) != required_summary_count:
                    errors.append(f"Must have exactly {required_summary_count} summary file(s), found {len(summary_files)}")

            # Enforce phase-4 decision support artifacts, if configured
            decision_ok, decision_errors = self.check_decision_support(phase_number, feature_dir)
            if not decision_ok:
                errors.extend(decision_errors)

        return len(errors) == 0, errors

    def check_decision_support(self, phase_number: int, feature_dir: str) -> Tuple[bool, List[str]]:
        """Validate required decision-support artifacts for a phase."""
        phase_info = self.get_phase_info(phase_number)
        if not phase_info:
            return False, [f"Invalid phase: {phase_number}"]

        decision_support = phase_info.get('decision_support', {})
        required_artifacts = decision_support.get('required_artifacts', [])
        required_json_keys = decision_support.get('required_json_keys', [])

        if not required_artifacts:
            return True, []

        errors = []
        for artifact in required_artifacts:
            artifact_path = artifact.replace('{feature_dir}', feature_dir)
            if not os.path.isabs(artifact_path):
                artifact_path = os.path.join(feature_dir, artifact_path)

            if not os.path.exists(artifact_path):
                errors.append(f"Missing required decision-support artifact: {artifact_path}")
                continue

            if required_json_keys and artifact_path.endswith('.json'):
                try:
                    with open(artifact_path, 'r') as f:
                        payload = json.load(f)
                except Exception as e:
                    errors.append(f"Invalid JSON decision-support artifact: {artifact_path} ({e})")
                    continue

                for key in required_json_keys:
                    if key not in payload:
                        errors.append(f"Decision-support artifact missing required key '{key}': {artifact_path}")

        return len(errors) == 0, errors

    def validate_decision_support_command(
            self,
            phase_number: int,
            command: str,
            check_type: str = 'required_preflight') -> Tuple[bool, str]:
        """Validate whether a command matches configured decision-support commands."""
        phase_info = self.get_phase_info(phase_number)
        if not phase_info:
            return False, f"Invalid phase: {phase_number}"

        decision_support = phase_info.get('decision_support', {})
        configured = decision_support.get(check_type, [])
        if not configured:
            return True, f"No decision-support command requirements configured for {check_type}"

        normalized = command.strip()
        for allowed in configured:
            # Allow placeholder-based template comparison
            template = allowed.replace('{feature_dir}', '')
            if normalized == allowed or normalized.startswith(template.strip()):
                return True, f"Decision-support command allowed ({check_type})"
            if 'meta_controller.py' in allowed and 'meta_controller.py' in normalized:
                return True, f"Decision-support command allowed ({check_type})"

        return False, f"Command does not match configured decision-support {check_type} commands"

    def get_next_fr_number(self, feature_dir: str = "feature-requests") -> str:
        """Get next available FR number by scanning existing directories"""
        if not os.path.exists(feature_dir):
            return "FR-001"

        # Find all FR-XXX directories
        fr_pattern = re.compile(r'^FR-(\d{3})')
        max_number = 0

        for entry in os.listdir(feature_dir):
            match = fr_pattern.match(entry)
            if match:
                number = int(match.group(1))
                max_number = max(max_number, number)

        # Increment and format
        next_number = max_number + 1
        return f"FR-{next_number:03d}"

    def format_phase_info(self, phase_number: int, as_json: bool = False) -> str:
        """Format phase information for display"""
        phase_info = self.get_phase_info(phase_number)
        if not phase_info:
            return json.dumps({"error": f"Invalid phase: {phase_number}"}) if as_json else f"Invalid phase: {phase_number}"

        if as_json:
            return json.dumps(phase_info, indent=2)

        # Human-readable format
        output = []
        output.append(f"\n{'=' * 60}")
        output.append(f"Phase {phase_number}: {phase_info.get('name', 'Unknown')}")
        output.append(f"{'=' * 60}")
        output.append(f"\nDescription: {phase_info.get('description', 'N/A')}")

        permissions = phase_info.get('permissions', {})

        # Read permissions
        output.append(f"\nRead Permissions:")
        read_patterns = permissions.get('read', [])
        if read_patterns:
            for pattern in read_patterns[:5]:  # Show first 5
                output.append(f"  ✅ {pattern}")
            if len(read_patterns) > 5:
                output.append(f"  ... and {len(read_patterns) - 5} more")
        else:
            output.append("  ❌ No read permissions")

        # Write permissions
        output.append(f"\nWrite Permissions:")
        write_patterns = permissions.get('write', [])
        if write_patterns:
            for pattern in write_patterns:
                output.append(f"  ✅ {pattern}")
        else:
            output.append("  ❌ No write permissions")

        # Bash permissions
        output.append(f"\nBash Commands:")
        bash_config = permissions.get('bash', {})
        allowed = bash_config.get('allowed_commands', [])
        forbidden = bash_config.get('forbidden_commands', [])

        if allowed:
            output.append("  Allowed:")
            for cmd in allowed[:10]:  # Show first 10
                output.append(f"    ✅ {cmd}")
            if len(allowed) > 10:
                output.append(f"    ... and {len(allowed) - 10} more")

        if forbidden:
            output.append("  Forbidden:")
            for cmd in forbidden[:5]:  # Show first 5
                output.append(f"    ❌ {cmd}")

        # Deliverables
        output.append(f"\nRequired Deliverables:")
        deliverables = phase_info.get('deliverables', [])
        for deliverable in deliverables:
            name = deliverable.get('name', 'Unknown')
            desc = deliverable.get('description', 'N/A')
            output.append(f"  📋 {name}: {desc}")

        # Next phase
        next_phase = phase_info.get('next_phase')
        requires_approval = phase_info.get('requires_manual_approval', False)
        output.append(f"\nNext Phase: {next_phase if next_phase else 'Workflow Complete'}")
        if requires_approval:
            output.append("⚠️  Manual approval required before proceeding")

        output.append(f"{'=' * 60}\n")

        return "\n".join(output)


def main():
    parser = argparse.ArgumentParser(description='Validate feature request workflow phase actions')

    parser.add_argument('--phase', type=int, choices=[1, 2, 3, 4],
                        help='Phase number (1-4)')

    parser.add_argument('--action', choices=['info', 'read', 'write', 'bash'],
                        help='Action to validate')

    parser.add_argument('--path', type=str,
                        help='File path for read/write validation')

    parser.add_argument('--command', type=str,
                        help='Bash command for bash validation')

    parser.add_argument('--feature-name', type=str,
                        help='Validate feature name')

    parser.add_argument('--check-deliverables', action='store_true',
                        help='Check if all phase deliverables are complete')

    parser.add_argument('--check-decision-support', action='store_true',
                        help='Check required decision-support artifacts for a phase')

    parser.add_argument('--feature-dir', type=str,
                        help='Feature directory for deliverable checking')

    parser.add_argument('--next-fr-number', action='store_true',
                        help='Get next available FR number')

    parser.add_argument('--json', action='store_true',
                        help='Output results as JSON')

    parser.add_argument('--workflow-config', type=str,
                        default='.claude/skills/feature-request/references/feature-workflow.yaml',
                        help='Path to workflow configuration YAML')

    args = parser.parse_args()

    # Initialize validator
    try:
        validator = PhaseValidator(args.workflow_config)
    except FileNotFoundError:
        print(f"❌ Error: Workflow config not found: {args.workflow_config}", file=sys.stderr)
        sys.exit(1)
    except Exception as e:
        print(f"❌ Error loading workflow config: {e}", file=sys.stderr)
        sys.exit(1)

    # Handle different actions
    if args.feature_name:
        # Validate feature name
        valid, message = validator.validate_feature_name(args.feature_name)
        if args.json:
            print(json.dumps({"valid": valid, "message": message}))
        else:
            print(f"{'✅' if valid else '❌'} {message}")
        sys.exit(0 if valid else 1)

    elif args.next_fr_number:
        # Get next FR number
        next_fr = validator.get_next_fr_number()
        if args.json:
            print(json.dumps({"next_fr_number": next_fr}))
        else:
            print(f"Next FR number: {next_fr}")
        sys.exit(0)

    elif args.check_deliverables:
        # Check phase deliverables
        if not args.phase or not args.feature_dir:
            print("❌ Error: --phase and --feature-dir required for deliverable checking", file=sys.stderr)
            sys.exit(1)

        complete, errors = validator.check_deliverables(args.phase, args.feature_dir)
        if args.json:
            print(json.dumps({"complete": complete, "errors": errors}))
        else:
            if complete:
                print(f"✅ All deliverables complete for phase {args.phase}")
            else:
                print(f"❌ Deliverable errors for phase {args.phase}:")
                for error in errors:
                    print(f"  - {error}")
        sys.exit(0 if complete else 1)

    elif args.check_decision_support:
        # Check decision-support artifacts
        if not args.phase or not args.feature_dir:
            print("❌ Error: --phase and --feature-dir required for decision-support checking", file=sys.stderr)
            sys.exit(1)

        complete, errors = validator.check_decision_support(args.phase, args.feature_dir)
        if args.json:
            print(json.dumps({"complete": complete, "errors": errors}))
        else:
            if complete:
                print(f"✅ Decision-support checks complete for phase {args.phase}")
            else:
                print(f"❌ Decision-support errors for phase {args.phase}:")
                for error in errors:
                    print(f"  - {error}")
        sys.exit(0 if complete else 1)

    elif args.action:
        if not args.phase:
            print("❌ Error: --phase required for action validation", file=sys.stderr)
            sys.exit(1)

        if args.action == 'info':
            # Display phase info
            print(validator.format_phase_info(args.phase, args.json))
            sys.exit(0)

        elif args.action == 'read':
            if not args.path:
                print("❌ Error: --path required for read validation", file=sys.stderr)
                sys.exit(1)

            valid, message = validator.validate_read(args.phase, args.path)
            if args.json:
                print(json.dumps({"valid": valid, "message": message}))
            else:
                print(f"{'✅' if valid else '❌'} {message}")
            sys.exit(0 if valid else 1)

        elif args.action == 'write':
            if not args.path:
                print("❌ Error: --path required for write validation", file=sys.stderr)
                sys.exit(1)

            valid, message = validator.validate_write(args.phase, args.path)
            if args.json:
                print(json.dumps({"valid": valid, "message": message}))
            else:
                print(f"{'✅' if valid else '❌'} {message}")
            sys.exit(0 if valid else 1)

        elif args.action == 'bash':
            if not args.command:
                print("❌ Error: --command required for bash validation", file=sys.stderr)
                sys.exit(1)

            valid, message = validator.validate_bash_command(args.phase, args.command)
            if args.json:
                print(json.dumps({"valid": valid, "message": message}))
            else:
                print(f"{'✅' if valid else '❌'} {message}")
            sys.exit(0 if valid else 1)

    else:
        parser.print_help()
        sys.exit(1)


if __name__ == '__main__':
    main()

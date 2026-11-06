# Runtime Bay observes ZLE's editable command line; it never reads terminal output
# or captures input while a program (including a password prompt) owns the TTY.
autoload -Uz add-zsh-hook add-zle-hook-widget
typeset -ga _RUNTIMEBAY_ASSIST_MARKS=()
typeset -gA _RUNTIMEBAY_ASSIST_MAN_SHORT=() _RUNTIMEBAY_ASSIST_MAN_LONG=() _RUNTIMEBAY_ASSIST_MAN_SHORT_ARG=() _RUNTIMEBAY_ASSIST_MAN_LONG_ARG=() _RUNTIMEBAY_ASSIST_MAN_CHECKED=()
typeset -gA _RUNTIMEBAY_ASSIST_COBRA_COMMANDS=(
  docker 1 podman 1 kubectl 1 helm 1 gh 1 kind 1 k3d 1 hugo 1
  goreleaser 1 skaffold 1 buf 1 dagger 1 kustomize 1 stern 1 istioctl 1
)
typeset -gA _RUNTIMEBAY_ASSIST_SUBCOMMAND_MAN_COMMANDS=(git 1 launchctl 1 security 1 defaults 1)

function _runtimebay_assist_mark() {
  region_highlight+=("$1")
  _RUNTIMEBAY_ASSIST_MARKS+=("$1")
}

function _runtimebay_assist_load_man_options() {
  local name=$1 output synopsis line token short_options='' long_options='|' short_args='' long_args='' remaining
  [[ -n "$name" && "$name" != *[^A-Za-z0-9_.+-]* ]] || return 1
  (( ${+_RUNTIMEBAY_ASSIST_MAN_CHECKED[$name]} )) && return $(( ${+_RUNTIMEBAY_ASSIST_MAN_SHORT[$name]} ? 0 : 1 ))
  _RUNTIMEBAY_ASSIST_MAN_CHECKED[$name]=1
  output=$(PAGER=cat MANPAGER=cat /usr/bin/man "$name" 2>/dev/null | /usr/bin/col -b 2>/dev/null) || return 1
  [[ "$output" == *$'\nSYNOPSIS\n'* ]] || return 1
  synopsis=${output#*$'\nSYNOPSIS\n'}
  # Restrict parsing to SYNOPSIS. Descriptive examples can contain options
  # that belong to a different command or are not accepted by this version.
  remaining=''
  for line in ${(f)synopsis}; do
    [[ "$line" =~ '^[A-Z][A-Z ]*$' ]] && break
    remaining+=" $line"
  done
  synopsis=$remaining
  local -a tokens
  tokens=(${(z)synopsis})
  local -i token_index
  for (( token_index = 1; token_index <= ${#tokens}; token_index++ )); do
    token=${tokens[token_index]}
    token=${token##[\[\(,]}
    token=${token%%[\]\),|]}
    if [[ "$token" =~ '^--[A-Za-z0-9_-]+(=.*)?$' ]]; then
      local long_token=$token
      token=${token%%=*}
      long_options+="$token|"
      if [[ "$long_token" != *=* && token_index -lt ${#tokens} && "${tokens[token_index + 1]}" != -* && "${tokens[token_index + 1]}" != '|' ]]; then
        long_args+="|$token"
      fi
    elif [[ "$token" =~ '^-[A-Za-z0-9@?%,]+$' ]]; then
      token=${token#-}
      local -i char_index
      for (( char_index = 1; char_index <= ${#token}; char_index++ )); do
        short_options+="|${token[char_index]}"
      done
      if [[ "$token" == [A-Za-z0-9@?] && token_index -lt ${#tokens} && "${tokens[token_index + 1]}" != -* && "${tokens[token_index + 1]}" != '|' ]]; then
        short_args+="|$token"
      fi
    fi
  done
  [[ -n "$short_options" || "$long_options" != '|' ]] || return 1
  _RUNTIMEBAY_ASSIST_MAN_SHORT[$name]="$short_options|"
  _RUNTIMEBAY_ASSIST_MAN_LONG[$name]="$long_options"
  _RUNTIMEBAY_ASSIST_MAN_SHORT_ARG[$name]="$short_args|"
  _RUNTIMEBAY_ASSIST_MAN_LONG_ARG[$name]="$long_args|"
}

function _runtimebay_assist_validate_man_options() {
  local name=$1 buffer=$2 option token char invalid_option='' option_start metadata_name=$1 candidate short_options long_options short_args long_args skip_next=false
  shift 2
  # Prefer a command-specific manual page such as git-checkout when one exists.
  # Merge it with the root page so global flags (for example git -C) remain valid.
  _runtimebay_assist_load_man_options "$name"
  short_options=${_RUNTIMEBAY_ASSIST_MAN_SHORT[$name]-}
  long_options=${_RUNTIMEBAY_ASSIST_MAN_LONG[$name]-'|'}
  short_args=${_RUNTIMEBAY_ASSIST_MAN_SHORT_ARG[$name]-'|'}
  long_args=${_RUNTIMEBAY_ASSIST_MAN_LONG_ARG[$name]-'|'}
  if (( ${+_RUNTIMEBAY_ASSIST_SUBCOMMAND_MAN_COMMANDS[$name]} )); then
    for candidate in "$@"; do
      [[ "$candidate" == -* ]] && continue
      if _runtimebay_assist_load_man_options "${name}-${candidate}"; then
        metadata_name="${name}-${candidate}"
        short_options+="${_RUNTIMEBAY_ASSIST_MAN_SHORT[$metadata_name]-}"
        long_options+="${_RUNTIMEBAY_ASSIST_MAN_LONG[$metadata_name]-|}"
        short_args+="${_RUNTIMEBAY_ASSIST_MAN_SHORT_ARG[$metadata_name]-|}"
        long_args+="${_RUNTIMEBAY_ASSIST_MAN_LONG_ARG[$metadata_name]-|}"
        break
      fi
    done
  fi
  [[ -n "$short_options" || "$long_options" != '|' ]] || return 0
  for option in "$@"; do
    if [[ $skip_next == true ]]; then skip_next=false; continue; fi
    [[ "$option" == -- ]] && break
    [[ "$option" == -* && "$option" != -- ]] || continue
    if [[ "$option" == --* ]]; then
      token=${option%%=*}
      [[ "$long_options" == *"|$token|"* ]] || { invalid_option=$option; break; }
      [[ "$option" == *=* ]] || { [[ "$long_args" == *"|$token|"* ]] && skip_next=true; }
      continue
    fi
    token=${option#-}
    local -i char_index
    for (( char_index = 1; char_index <= ${#token}; char_index++ )); do
      char=${token[char_index]}
      [[ "$short_options" == *"|$char|"* ]] || { invalid_option=$option; break 2; }
      if [[ "$short_args" == *"|$char|"* ]]; then
        if (( char_index < ${#token} )); then break; fi
        skip_next=true
        break
      fi
    done
  done
  [[ -n "$invalid_option" ]] || return 0
  option_start=${buffer[(i)$invalid_option]}
  if (( option_start > 0 )); then
    option_start=$((option_start - 1))
    _runtimebay_assist_mark "$option_start $((option_start + ${#invalid_option})) fg=#ff8e91,bold"
  fi
}

function _runtimebay_assist_validate_cobra() {
  local command=$1 name=$2 buffer=$3 probe_output invalid_option option_start candidate probe_key result
  shift 3
  # Only invoke the completion protocol for commands explicitly registered as
  # Cobra CLIs. Never send synthetic arguments to arbitrary utilities.
  (( ${+_RUNTIMEBAY_ASSIST_COBRA_COMMANDS[$name]} )) || return 1
  [[ -n "${commands[$name]-}" ]] || return 1
  probe_key="${name}:${(j: :)argv}"
  if [[ "$probe_key" == "${_RUNTIMEBAY_ASSIST_COBRA_LAST_KEY-}" ]]; then
    result=${_RUNTIMEBAY_ASSIST_COBRA_LAST_RESULT-unsupported}
  else
    local -a probe_args=("$command" __complete)
    probe_args+=("$@" "")
    probe_output=$("${probe_args[@]}" 2>&1)
    if [[ ! "$probe_output" =~ $'\n:[0-9]+($|\n)' ]]; then
      result=unsupported
    else
      result=valid
      if [[ "$probe_output" == *"unknown shorthand flag"* || "$probe_output" == *"unknown flag"* || "$probe_output" == *"unknown option"* ]]; then
        invalid_option=${argv[-1]-}
        for candidate in "$@"; do
          if [[ "$candidate" == -* && ( "$probe_output" == *"in $candidate"* || "$probe_output" == *"unknown flag: $candidate"* || "$probe_output" == *"unknown option $candidate"* ) ]]; then
            invalid_option=$candidate; break
          fi
        done
        result=$invalid_option
      fi
    fi
    typeset -g _RUNTIMEBAY_ASSIST_COBRA_LAST_KEY="$probe_key"
    typeset -g _RUNTIMEBAY_ASSIST_COBRA_LAST_RESULT="$result"
  fi
  [[ $result != unsupported ]] || return 1
  [[ $result != valid ]] || return 0
  invalid_option=$result
  option_start=${buffer[(i)$invalid_option]}
  if (( option_start > 0 )); then
    option_start=$((option_start - 1))
    _runtimebay_assist_mark "$option_start $((option_start + ${#invalid_option})) fg=#ff8e91,bold"
  fi
  return 0
}

function _runtimebay_assist_json_quote() {
  local value=$1 result='"' character
  local index
  for (( index = 1; index <= ${#value}; index++ )); do
    character=${value[index]}
    case "$character" in
      (\\) result+='\\\\' ;;
      ('"') result+='\\"' ;;
      ($'\n') result+='\\n' ;;
      ($'\r') result+='\\r' ;;
      ($'\t') result+='\\t' ;;
      (*) result+="$character" ;;
    esac
  done
  result+='"'
  REPLY=$result
  [[ ${2-} == print ]] && print -rn -- "$REPLY"
}

function _runtimebay_assist_redraw() {
  local buffer="$BUFFER" cursor="$CURSOR" command="" command_name="" recognized=false token="" glob="" candidate="" json_buffer json_cwd json_command commands_json='[' paths_json='[' options_json='[' payload
  local -a words command_matches path_matches
  local mark remove_index completed_option
  # ZLE can retain region highlights between redraws. Remove only the exact
  # spans Runtime Bay added last time so corrected input loses its red mark.
  for mark in "${_RUNTIMEBAY_ASSIST_MARKS[@]}"; do
    for (( remove_index = ${#region_highlight}; remove_index >= 1; remove_index-- )); do
      if [[ ${region_highlight[remove_index]} == "$mark" ]]; then region_highlight[remove_index]=(); fi
    done
  done
  _RUNTIMEBAY_ASSIST_MARKS=()
  words=(${(z)BUFFER})
  command=${words[1]-}
  command_name=${command:t}

  if [[ -n "$command_name" ]]; then
    if (( ${+commands[$command_name]} || ${+builtins[$command_name]} || ${+functions[$command_name]} || ${+aliases[$command_name]} || ${+reswords[$command_name]} )); then
      recognized=true
    elif [[ " if then else elif fi for foreach while until do done case esac repeat select function time coproc " == *" $command_name "* ]]; then
      recognized=true
    elif [[ "$command" == */* && -x "$command" ]]; then
      recognized=true
    fi
    if [[ "$BUFFER" == "$command"* ]]; then
      if [[ $recognized == true ]]; then
        _runtimebay_assist_mark "0 ${#command} fg=#8fe3c2,bold"
      elif [[ -n "${words[1]-}" ]]; then
        if [[ ${BUFFER:${#command}} == [[:space:]]* ]]; then
          _runtimebay_assist_mark "0 ${#command} fg=#ff8e91,bold"
        else
          _runtimebay_assist_mark "0 ${#command} fg=#ffd18a"
        fi
      fi
    fi
  fi

  # Mark an unfinished quote as soon as the user has begun a command. This is
  # intentionally lexical and never evaluates the editable buffer.
  local quote='' quote_start=-1 escaped=false character index
  for (( index = 1; index <= ${#buffer}; index++ )); do
    character=${buffer[index]}
    if [[ $escaped == true ]]; then
      escaped=false
    elif [[ $character == '\\' && $quote != "'" ]]; then
      escaped=true
    elif [[ -n $quote ]]; then
      if [[ $character == $quote ]]; then quote=''; quote_start=-1; fi
    elif [[ $character == "'" || $character == '"' ]]; then
      quote=$character; quote_start=$((index - 1))
    fi
  done
  if [[ -n $quote && -n $command_name ]]; then
    _runtimebay_assist_mark "$quote_start ${#buffer} fg=#ff8e91,bold"
  fi

  # Ask a clean, non-interactive Zsh parser to check the completed syntax. The
  # trailing no-op accepts normal unfinished continuations (for example
  # `if ...; then `); -n guarantees that user commands are never executed.
  local syntax_output syntax_suffix syntax_incomplete
  if [[ "$buffer" == *[[:space:]] && -n $command_name && -z $quote && "$buffer" != "${_RUNTIMEBAY_ASSIST_SYNTAX_LAST-}" ]]; then
    typeset -g _RUNTIMEBAY_ASSIST_SYNTAX_LAST="$buffer"
    if ! syntax_output=$(command zsh -f -n -c "$buffer :" 2>&1); then
      syntax_incomplete=false
      for syntax_suffix in ':; fi' ':; done' ':; esac' ':; }'; do
        if command zsh -f -n -c "$buffer $syntax_suffix" >/dev/null 2>&1; then syntax_incomplete=true; break; fi
      done
      [[ $syntax_incomplete == true ]] || _runtimebay_assist_mark "0 ${#buffer} fg=#ff8e91,bold"
    fi
  fi

  # Use the command's installed manual synopsis as read-only option metadata.
  # Never invoke the command being edited as a validator: utilities such as rm
  # would interpret a synthetic completion request as a real operation.
  completed_option=${words[-1]-}
  if [[ "$buffer" == *[[:space:]] && "$completed_option" == -* && "$completed_option" != -- && $recognized == true ]]; then
    if ! _runtimebay_assist_validate_cobra "$command" "$command_name" "$buffer" "${words[@]:1}"; then
      _runtimebay_assist_validate_man_options "$command_name" "$buffer" "${words[@]:1}"
    fi
  fi

  token="${buffer[1,$cursor]}"
  token="${token##*[[:space:]]}"
  if [[ "$buffer" == "$token"* && -n "$token" ]]; then
    local command_length=${#token} name
    for name in ${(k)commands} ${(k)builtins} ${(k)functions}; do
      if [[ ${#name} -ge $command_length && "${name[1,$command_length]}" == "$token" ]]; then
        command_matches+=("$name")
        (( ${#command_matches[@]} >= 20 )) && break
      fi
    done
    command_matches=(${(on)command_matches})
    command_matches=(${command_matches[1,10]})
  fi

  local path_context=false
  case "$command_name" in
    cd|pushd|ls|cat|less|head|tail|touch|mkdir|rmdir|rm|cp|mv|chmod|chown|open|python|python3|node|java|javac|go|grep|find|source|\. ) path_context=true ;;
  esac
  if [[ "$token" == */* || ( "$buffer" != "$token" && $path_context == true ) ]]; then
    if [[ "$token" == /* ]]; then
      glob="$token*"
    elif [[ "$token" == '~/'* ]]; then
      glob="$HOME/${token#\~/}*"
    elif [[ "$token" == ./* || "$token" == ../* ]]; then
      glob="$PWD/$token*"
    else
      glob="$PWD/$token*"
    fi
    local path_prefix=${glob%\*}
    path_matches=( "$path_prefix"*(N) )
    local path_count=0 path_display
    for candidate in "${path_matches[@]}"; do
      if [[ "$command_name" == cd || "$command_name" == pushd ]]; then
        [[ -d "$candidate" ]] || continue
      fi
      [[ -d "$candidate" ]] && candidate+="/"
      if [[ "$token" == /* ]]; then
        path_display="$candidate"
      elif [[ "$token" == '~/'* ]]; then
        path_display="~/${candidate#$HOME/}"
      elif [[ "$token" == ./* ]]; then
        path_display="./${candidate#$PWD/}"
      elif [[ "$token" == ../* ]]; then
        path_display="${candidate#$PWD/}"
      else
        path_display="${candidate#$PWD/}"
      fi
      # The frontend displays and inserts only the part the user has not typed.
      [[ "$path_display" == "$token"* ]] && path_display="${path_display#"$token"}"
      [[ -n "$path_display" ]] || continue
      paths_json+="$(_runtimebay_assist_json_quote "$path_display" print),"
      (( ++path_count >= 10 )) && break
    done
  fi

  # Suggest flags from the installed manual synopsis. Show them after a
  # command-space or while the current token is an option prefix (for example
  # `ls ` or `ls -l`), never while the user is entering a positional value.
  local option_context=false option_prefix='-' metadata_name short_options long_options option_char
  if [[ $recognized == true && ( "$token" == -* || ( -z "$token" && "$buffer" == *[[:space:]] ) ) ]]; then
    option_context=true
    [[ "$token" == -* ]] && option_prefix="$token"
    _runtimebay_assist_load_man_options "$command_name"
    short_options=${_RUNTIMEBAY_ASSIST_MAN_SHORT[$command_name]-}
    long_options=${_RUNTIMEBAY_ASSIST_MAN_LONG[$command_name]-'|'}
    if (( ${+_RUNTIMEBAY_ASSIST_SUBCOMMAND_MAN_COMMANDS[$command_name]} )); then
      for candidate in "${words[@]:1}"; do
        [[ "$candidate" == -* ]] && continue
        if _runtimebay_assist_load_man_options "${command_name}-${candidate}"; then
          metadata_name="${command_name}-${candidate}"
          short_options+="${_RUNTIMEBAY_ASSIST_MAN_SHORT[$metadata_name]-}"
          long_options+="${_RUNTIMEBAY_ASSIST_MAN_LONG[$metadata_name]-|}"
          break
        fi
      done
    fi
    if [[ -n "$short_options" || "$long_options" != '|' ]]; then
      local -a option_candidates
      local -i option_index
      for option_char in ${(s:|:)short_options}; do
        [[ -n "$option_char" ]] && option_candidates+=("-${option_char}")
      done
      for candidate in ${(s:|:)long_options}; do
        [[ -n "$candidate" ]] && option_candidates+=("$candidate")
      done
      option_candidates=(${(u)option_candidates})
      option_candidates=(${(on)option_candidates})
      for candidate in "${option_candidates[@]}"; do
        [[ "$candidate" == "$option_prefix"* ]] || continue
        options_json+="$(_runtimebay_assist_json_quote "$candidate" print),"
      done
    fi
  fi

  for candidate in "${command_matches[@]}"; do commands_json+="$(_runtimebay_assist_json_quote "$candidate" print),"; done
  [[ $commands_json == *',' ]] && commands_json=${commands_json%,}
  [[ $paths_json == *',' ]] && paths_json=${paths_json%,}
  [[ $options_json == *',' ]] && options_json=${options_json%,}
  commands_json+=']'; paths_json+=']'; options_json+=']'
  _runtimebay_assist_json_quote "$buffer"; json_buffer=$REPLY
  _runtimebay_assist_json_quote "$PWD"; json_cwd=$REPLY
  _runtimebay_assist_json_quote "$command_name"; json_command=$REPLY
  printf -v payload '{"buffer":%s,"cursor":%d,"cwd":%s,"command":%s,"recognized":%s,"commands":%s,"paths":%s,"options":%s}' "$json_buffer" "$cursor" "$json_cwd" "$json_command" "$recognized" "$commands_json" "$paths_json" "$options_json"
  [[ "$payload" == "${_RUNTIMEBAY_ASSIST_LAST-}" ]] && return
  typeset -g _RUNTIMEBAY_ASSIST_LAST="$payload"
  if [[ -n "${RUNTIMEBAY_ASSIST_FILE-}" ]]; then
    print -rn -- "$payload" >| "$RUNTIMEBAY_ASSIST_FILE"
  fi
}

if [[ -o interactive ]]; then
  add-zle-hook-widget line-pre-redraw _runtimebay_assist_redraw
fi

# Only a plain local interactive invocation is redirected. Flags, pipelines,
# remote shells, and `command nano` retain the normal terminal editor.
if [[ -o interactive && -n ${RUNTIMEBAY_EDITOR_DIR-} && -d $RUNTIMEBAY_EDITOR_DIR ]] && (( ! ${+functions[nano]} )); then
  function nano() {
    if (( $# == 1 )) && [[ $1 != -* && -t 0 && -t 1 ]]; then
      local filename=${1:a} request payload
      _runtimebay_assist_json_quote "$filename"; payload=$REPLY
      request=$(/usr/bin/mktemp "$RUNTIMEBAY_EDITOR_DIR/.pending.XXXXXXXX") || { command nano "$@"; return; }
      print -rn -- "$payload" >| "$request"
      /bin/mv -- "$request" "$RUNTIMEBAY_EDITOR_DIR/request-${request:t}"
      print -r -- "Opened in Runtime Bay editor: $filename"
    else
      command nano "$@"
    fi
  }
fi

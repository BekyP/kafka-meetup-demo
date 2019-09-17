display_help() {
    echo "Usage: $(basename $0) <cfg1> [cfg2 [cfg3...]]"
    echo "    cfgX      : properties configuration files (either JSON or properties format)"
    echo "                later cfg overrides values provided in earlier cfg, so common cfg must be specified first"
}

# append key+value in JSON format
append_key_value() {
    local key="${1}"
    local value="${2}"

    _retval="\"${key}\": \"${value}\""
}

# extract config from properties file as JSON
connector_config_template() {
    local connector_name="${1}"
    local config_file="${2}"
    local nested="${3}"
    local config="{"

    local wrapper=""
    [[ "${nested}" == true ]] && wrapper=" \"config\": {"

    config="${config}${wrapper}"

    while IFS= read -r line; do
        local key="${line%%=*}"
        local value="${line#*=}"
        if [[ $value =~ (^\$\{env\.)(.*)(\}) ]] && tmp="${BASH_REMATCH[2]}"; then
            eval "value=\$$tmp"
        fi
        append_key_value "${key}" "${value}"
        config="${config}${_retval},"
    done < <( sed ':x; /\\$/ { N; s/\\\n//; tx }' "${config_file}" | grep -H -v ^# | cut -d : -f 1 --complement | grep -v -e '^[[:space:]]*$' | grep '=' )

    [[ "${nested}" == true ]] && wrapper="}"

    config="${config}}"
    config="${config%%',}'}"
    config="${config}}${wrapper}"

    _retval="$( echo "${config}" | eval ${FORMAT_CMD} )"
}

# join two JSON configs
join_json() {
    local json1="${1}"
    local json2="${2}"

    if [[ -z "${json1}" || -z "${json2}" ]]; then
        _retval="${json1}${json2}"
    else
        _retval=$(echo "[${json1}, ${json2}]" | jq '.[0] * .[1]')
    fi
}

# extract config from JSON file
extract_json_config() {
    local config_file="${1}"
    # If it is nested, extract only the config field.
    local only_config="${2}"

    local parsed_json=""
    # Treating here the JSON contents as flat json, or nested with a specific field called
    # "config" is good enough for now.
    if [[ ${only_config} == true ]]; then
        parsed_json=$( cat "${config_file}" | jq -e '.config' ) \
            || die "Missing 'config' property in JSON file '${config_file}'."
    else
        parsed_json=$( cat "${config_file}" )
    fi
    _retval="${parsed_json}"
}

# check whether we have JSON contents
is_json() {
    local config_file="${1}"

    cat "${config_file}" | eval ${FORMAT_CMD} > /dev/null 2>&1
    return $?
}

# exit with an error message
die() {
    echo "$@"
    exit ${ERROR_CODE}
}

# parse provided config files and deploy connector
connect_config_command() {
    local parsed_json=""

    for config_file in "$@"; do

        [[ ! -f "${config_file}" ]] \
            && die "Can't load connector configuration. Config file '${config_file}' does not exist."

        # Check whether we have json contents.
        is_json "${config_file}"
        status=$?

        if [[ ${status} -eq 0 ]]; then
            # It's JSON format load it.
            extract_json_config "${config_file}" "true"
        else
            file "${config_file}" | grep "ASCII" > /dev/null 2>&1 \
                || die "Unknown type of config file '${config_file}'"

            # Potentially properties file. Try to load it.
            connector_config_template "${connector_name}" "${config_file}" "false"
        fi

        join_json "${parsed_json}" "${_retval}"
        parsed_json="${_retval}"
    done

    connector_name=$( echo "${parsed_json}" | jq -r -e '.name' ) \
        || die "Missing 'name' property from connectors properties files."


    curl --max-time "${_default_curl_timeout}" -S -s -X PUT \
        -H "Content-Type: application/json" \
        -d "${parsed_json}" \
        "${connect_url}:${connect_port}/connectors/${connector_name}/config" \
        | eval ${FORMAT_CMD}
}

##########
# main
##########

_default_curl_timeout=10
FORMAT_CMD="jq '.'"
ERROR_CODE=127


if [[ "$#" -lt 1 ]]; then
    display_help
    exit 1
fi

which jq > /dev/null 2>&1 \
    || die "Missing 'jq' tool."


connect_url="connect"
connect_port="8083"

connect_config_command "$@"

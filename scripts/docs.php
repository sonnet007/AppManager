#!/usr/bin/env php
<?php
/* SPDX-License-Identifier: GPL-3.0-or-later */

date_default_timezone_set('UTC');

// USAGE: php ./scripts/docs.php VERB [ARGS]
// VERBS:
// build <lang>     Build HTML from TeX using Pandoc for the given language.
// update           Extract strings from the TeX files and create base translation file.
// debug            Do experiments

// External requirements: Pandoc, pandoc-crossref, minify

require_once __DIR__ . "/utils.php";

const MAIN_TEX = 'main.tex';
const CUSTOM_CSS = 'custom.css';
const OUTPUT_FILENAME = 'index.html';
const STRINGS_XML = 'strings.xml';
const STRINGS_HEADER = <<<EOF
<?xml version="1.0" encoding="utf-8"?>
<!-- This file is auto-generated by ./scripts/docs.php. DO NOT EDIT THIS FILE. -->

EOF;
const RAW_DIR = './docs/raw';
const BASE_DIR = RAW_DIR . '/en';

/**
 * Build and minify the outputs from TeX.
 *
 * @param $lang Target language e.g. en, ru, ja, etc.
 */
function build_html($lang) {
    $pwd = RAW_DIR . '/' . $lang;
    $main_tex = $pwd . '/' . MAIN_TEX;
    $output_file = $pwd . '/' . OUTPUT_FILENAME;
    // Fixed directories
    $base_dir = getcwd() . '/' . BASE_DIR;
    $custom_css = $base_dir . '/' . CUSTOM_CSS;
    $lua_dir = $base_dir . '/lua';
    // Sequence must be preserved
    $lua_files = [
        'toc_generator.lua',
        'img_to_object.lua',
        'header_with_hyperlinks.lua',
        'alert_fix.lua'
    ];
    $cmd = 'cd "' .$pwd. '" && pandoc "' . MAIN_TEX . '" -c "' . CUSTOM_CSS .'" -o "' . OUTPUT_FILENAME . '" -t html5'
        . ' -f latex -s --toc -N --section-divs --default-image-extension=png -i -F pandoc-crossref --citeproc'
        . ' --highlight-style=monochrome --verbose';
    foreach ($lua_files as $lua_script) {
        $cmd .= ' --lua-filter="' . $lua_dir . '/' . $lua_script . '"';
    }
    // Run command
    passthru($cmd, $ret_val);
    if ($ret_val != 0) {
        echo 'Pandoc could not generate an HTML file.';
        exit(1);
    }

    // Read colours
    $main_contents = file_get_contents($main_tex);
    preg_match_all('/\\\definecolor\{(?<name>[^\}]+)\}\{HTML\}\{(?<color>[0-9a-fA-F]+)\}/', $main_contents, $matches);

    // Replace colours
    $to_search = array();
    $to_replace = array();
    foreach ($matches['name'] as $color_name) {
        array_push($to_search, "/style=\"background-color: ${color_name}\"/", "/style=\"color: ${color_name}\"/");
    }
    foreach ($matches['color'] as $color_value) {
        array_push($to_replace, "class=\"colorbox\" style=\"background-color: #${color_value}\"", "style=\"color: #${color_value}\"");
    }

    // Replace version name and date
    $am_version = system("grep -m1 versionName ./app/build.gradle | awk -F \\\" '{print $2}'", $ret_val);
    if ($ret_val != 0) {
        echo 'Could not get the versionName from ./app/build.gradle';
        exit(1);
    }
    $today = date('j F Y');
    array_push($to_replace, $am_version, $today);
    array_push($to_search, '/\$ABC\$APP-MANAGER-VERSION\$XYZ\$/', '/\$ABC\$USER-MANUAL-DATE\$XYZ\$/');
    $output_contents = file_get_contents($output_file);
    $output_contents = preg_replace($to_search, $to_replace, $output_contents);

    file_put_contents($output_file, $output_contents);

    // Minify CSS
    $cmd = "minify \"${custom_css}\" -o \"${base_dir}/../css/custom.css\"";
    system($cmd, $ret_val);
    if ($ret_val != 0) {
        echo "Could not minify custom.css";
        exit(1);
    }
    // Minify HTML
    $cmd = "minify \"${output_file}\" -o \"${pwd}/index.min.html\" && mv \"${pwd}/index.min.html\" \"${output_file}\"";
    system($cmd, $ret_val);
    if ($ret_val != 0) {
        echo "Could not minify index.html";
        exit(1);
    }
}

/**
 * Recursively parse all the \input command and gather all the included tex files from main.tex.
 *
 * @param $tex_files Relative links to the TeX files
 */
function collect_tex_files(&$tex_files, $base_dir = null, $tex_file = null) {
    if ($tex_file == null) {
        $base_dir = getcwd() . '/' . BASE_DIR;
        $tex_file = MAIN_TEX;
    }
    if (!file_exists($base_dir . '/' . $tex_file)) {
        echo "File ${tex_file} does not exist!";
        return;
    }
    array_push($tex_files, $tex_file);
    $contents = file_get_contents($base_dir . '/' . $tex_file);
    preg_match_all('/\\\input\{(?<tex_file>[^\}]+)\}/', $contents, $matches);
    foreach ($matches['tex_file'] as $t) {
        collect_tex_files($tex_files, $base_dir, $t);
    }
}

/**
 * Parse the given TeX file and return the parsed contents as a key-value pair.
 */
function get_tex_contents_assoc($tex_file) {
    $tex_file_contents = file_get_contents($tex_file);

    // Get all the titles
    preg_match_all('/((?<=section{)|(?<=subsection{)|(?<=subsubsection{)|(?<=chapter{)|(?<=caption{)|(?<=paragraph{))(?<raw_title>.*)(?<=\%\%##)(?<key>.*)(?=>>)/', $tex_file_contents, $matches);
    // Get titles from the raw titles
    $title_values = array();
    foreach ($matches['raw_title'] as $raw_title) {
        $c = 0; // number of extra {
        $len = strlen($raw_title);
        for ($i = 0; $i < $len; ++$i) {
            if ($raw_title[$i] == '{') {
                if ($i == 0 || $raw_title[$i - 1] != '\\') {
                    // Unescaped {
                    ++$c; // Increase { counter
                }
            } else if ($raw_title[$i] == '}') {
                if ($i == 0 || $raw_title[$i - 1] != '\\') {
                    // Unescaped }
                    --$c; // Decrease { counter since one match was found
                    if ($c < 0) {
                        // End of the title reached
                        array_push($title_values, substr($raw_title, 0, $i));
                        break;
                    }
                }
            }
        }
    }
    // Check keys for verification testing
    foreach ($matches['key'] as $key) {
        if (strlen($key) == 0) {
            echo "Warning: Empty raw title for key ${key}\n";
            continue;
        }
        if ($key[0] != '$') {
            echo "Warning: First letter of the title is not `$` (key: ${key})\n";
        }
        if (!preg_match('/^\$[a-zA-Z0-9-_\.]+$/', $key)) {
            echo "Warning: Key (${key}) didn't match the required Regex\n";
        }
    }
    // Convert to key => value pair
    $titles = array_combine($matches['key'], $title_values);

    // Extract all the contents
    preg_match_all('/(?<=\%\%!!)(?<key>.*)(?=<<)/', $tex_file_contents, $matches);
    $content_values = array();
    $offset = 0;
    foreach ($matches['key'] as $key) {
        $start_magic = '%%!!' . $key . "<<\n";
        $start_pos = strpos($tex_file_contents, $start_magic, $offset) + strlen($start_magic);
        $end_pos = strpos($tex_file_contents, "\n%%!!>>", $offset);
        $offset = $end_pos + 7;
        array_push($content_values, substr($tex_file_contents, $start_pos, $end_pos - $start_pos));
    }
    foreach ($matches['key'] as $key) {
        if (strlen($key) == 0) {
            echo "Warning: Empty raw title for key ${key}\n";
            continue;
        }
        if (!preg_match('/^[a-zA-Z0-9-_\.]+$/', $key)) {
            echo "Warning: Key (${key}) didn't match the required Regex\n";
        }
    }
    $contents = array_combine($matches['key'], $content_values);

    return array_merge($titles, $contents);
}

/**
 * Update base strings.xml
 */
function update_strings() {
    $base_dir = getcwd() . '/' . BASE_DIR;
    $strings_file = $base_dir . '/' . STRINGS_XML;
    $tex_files = array();
    // Gather all the tex files
    collect_tex_files($tex_files);
    $xml = new XMLWriter();
    $xml->openUri($strings_file);
    $xml->setIndent(true);
    $xml->setIndentString('    ');
    $xml->startDocument('1.0', 'utf-8');
    $xml->writeComment('This file is auto-generated by ./scripts/docs.php. DO NOT EDIT THIS FILE.');
    $xml->startElement('resources');
    $xml->writeAttribute('xmlns:xliff', 'urn:oasis:names:tc:xliff:document:1.2');
    foreach ($tex_files as $tex_file) {
        $contents = get_tex_contents_assoc($base_dir . '/' . $tex_file);
        // Replace `/` and `.tex` with `$`
        $key_prefix = preg_replace(['/\//', '/\.tex$/'], '$', $tex_file);
        foreach ($contents as $key => $val) {
            $xml->startElement('string');
            $xml->writeAttribute('name', $key_prefix . $key);
            $xml->writeRaw(android_escape_slash_newline($val));
            $xml->endElement(); // string
        }
    }
    $xml->endElement(); // resources
}

// MAIN //
if ($argc < 2) {
    echo 'Invalid number of arguments.';
    exit(1);
}

$verb = $argv[1];

switch($verb) {
    case 'build':
        if (!isset($argv[2])) {
            echo 'build <lang>';
            exit(1);
        }
        build_html($argv[2]);
        break;
    case 'update':
        update_strings();
        break;
    case 'debug':
        // todo
        break;
}
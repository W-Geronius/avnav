# Create object for the systray 
[System.Reflection.Assembly]::LoadWithPartialName('System.Windows.Forms')       | out-null
[System.Reflection.Assembly]::LoadWithPartialName('presentationframework')      | out-null
[System.Reflection.Assembly]::LoadWithPartialName('System.Drawing')          | out-null
[System.Reflection.Assembly]::LoadWithPartialName('WindowsFormsIntegration') | out-null


$dataDir=Join-Path "$env:USERPROFILE" '\AvNav'
$softwareBase=Join-Path "$env:LOCALAPPDATA" 'avnav'
$logDir=Join-Path "$dataDir" "log"
$serverLog="$logDir\service.log"
$serverError="$logDir\service-err.log"
$defaultUpdateUrl="https://wellenvogel.de/software/avnav/downloads/release/latest/avnav-latest.zip"

$icon = Join-Path $PSScriptRoot 'Chart60.ico'

#import dialogs
. "$PSScriptRoot\Dialogs.ps1"
################################################################################################################################"
# ACTIONS FROM THE SYSTRAY
################################################################################################################################"

 

# ----------------------------------------------------
# Part - Add the systray menu
# ----------------------------------------------------        

 

$Main_Tool_Icon = New-Object System.Windows.Forms.NotifyIcon
$Main_Tool_Icon.Text = "AvNav Server"
$Main_Tool_Icon.Icon = $icon
$Main_Tool_Icon.Visible = $true

$Menu_Start = New-Object System.Windows.Forms.MenuItem
$Menu_Start.Enabled = $false
$Menu_Start.Text = "Start"

$Menu_Stop = New-Object System.Windows.Forms.MenuItem
$Menu_Stop.Enabled = $true
$Menu_Stop.Text = "Stop"

$Menu_Install = New-Object System.Windows.Forms.MenuItem
$Menu_Install.Enabled = $true
$Menu_Install.Text = "Install"

$Menu_Exit = New-Object System.Windows.Forms.MenuItem
$Menu_Exit.Text = "Exit"

$Menu_Remove= New-Object System.Windows.Forms.MenuItem
$Menu_Remove.Text= "Remove"

$contextmenu = New-Object System.Windows.Forms.ContextMenu
$Main_Tool_Icon.ContextMenu = $contextmenu
$Main_Tool_Icon.contextMenu.MenuItems.AddRange($Menu_Start)
$Main_Tool_Icon.contextMenu.MenuItems.AddRange($Menu_Stop)
$Main_Tool_Icon.contextMenu.MenuItems.AddRange($Menu_Install)
$Main_Tool_Icon.contextMenu.MenuItems.AddRange($Menu_Remove)
$Main_Tool_Icon.contextMenu.MenuItems.AddRange($Menu_Exit)

function Kill-Tree {
    Param([int]$ppid)
    Get-CimInstance Win32_Process | Where-Object { $_.ParentProcessId -eq $ppid } | ForEach-Object { Kill-Tree $_.ProcessId }
    Stop-Process -Id $ppid -ErrorAction SilentlyContinue
}

$serverProcess = $null

function Set-Enable{
    $isInstalled=Test-Path -Path "$softwareBase"
    if ( $null -eq $serverProcess){
        $Menu_Stop.Enabled = $false
        $Menu_Start.Enabled = $isInstalled
        $Menu_Remove.Enabled = $isInstalled
        $Menu_Install.Enabled = $true
    }
    else{
        $Menu_Stop.Enabled = $true
        $Menu_Start.Enabled = $false
        $Menu_Remove.Enabled = $false
        $Menu_Install.Enabled = $false
    }
    if ($isInstalled){
        $Menu_Install.Text = 'Update'
    }
    else{
        $Menu_Install.Text= 'Install'
    }
    
}

$timer=New-Object System.Windows.Forms.Timer
$timer.Add_Tick({
    if ($null -ne $serverProcess){
        if ($serverProcess.HasExited){
            $timer.Enabled=$false
            $msg="AvNav stopped unexpectedly"
            $global:serverProcess=$null
            Set-Enable
            Show-InputDialog -WindowTitle "AvNav stopped" -Message "$msg" -ShowText $false 
        }
    }
 })
$timer.Interval=1000

function Run-Server {
    try{
    $exename = Join-Path "$softwareBase" "python\python.exe"
    if (-Not (Test-Path -Path "$exename")){
        throw "python at $exename not found"
    }
    $avnav = Join-Path "$softwareBase" "server\avnav_server.py"
    if (-Not (Test-Path -Path "$avnav")){
        throw "Avnav main at $avnav not found"
    }
    $xml= Join-Path "$dataDir" "avnav_server.xml"
    if (-Not (Test-Path -Path "$xml")){
        #create from template
        $template=Join-Path $PSScriptRoot "avnav_server.xml"
        if (-Not (Test-Path -Path $template )){
            throw "not config template at $template found"
        }
        if (-Not (Test-Path -Path "$dataDir")){
            mkdir -Force "$dataDir"
        }
        if (-Not (Test-Path -Path "$dataDir")){
            throw "unable to create data directory $dataDir"
        }
        Copy-Item "$template" -Destination "$xml"
    }
    $gdalpath=Join-Path "$softwareBase" "gdal\PFiles\GDAL"
    if (-Not (Test-Path -Path "$gdalpath")){
        throw "gdal not found at $gdalpath"
    }
    $gdaldata=Join-Path "$gdalpath" "gdal-data"
    $gdalpython=Join-Path "$softwareBase" "gdal\Lib\site-packages"
    $Env:GDAL_DATA = "$gdaldata"
    $Env:PYTHONPATH = "$gdalpython"
    $path=$Env:PATH
    if ($null -eq $path){
        $path=$gdalpath
    }
    else{
        if (-Not ($path -contains $gdalpath)){
            $path=$path+";"+$gdalpath
        }
    }
    $ENV:PATH="$path"
    $null=md -Force $logDir
    $viewer=Join-Path "$softwareBase" "viewer"
    $sounds=Join-Path "$softwareBase" "sounds"
    $arglist=@("$avnav",'-q','-w',"$dataDir","$xml","-u","viewer=""$viewer"",sounds=""$sounds""")
    $global:serverProcess = Start-Process -WindowStyle Hidden -FilePath "$exename" -ArgumentList $arglist -PassThru -RedirectStandardError "$serverError" -RedirectStandardOutput "$serverLog"
    $timer.Enabled=$true
    $timer.Start()
    }catch{
        Show-InputDialog -WindowTitle "StartupError" -Message $_.Exception.Message -ShowText $false
        return $false
    }
    return $true
}

function Run-Installer([string]$url){
    $arg = Join-Path $PSScriptRoot 'downloadAndInstall.ps1'
    $res = Start-Process powershell -ArgumentList "-Command ""$arg"" ""$url"" " -Wait -PassThru
    $msg="Installer result: "+ $res.ExitCode.ToString()
    Show-InputDialog -WindowTitle "Installer Result" -Message "$msg" -ShowText $false
    Set-Enable
}

function Remove-Installed{
    $confirm=Show-InputDialog -WindowTitle 'Confirm Removal' -Message "Really remove the installed AvNav software from `n $softwareBase ?" -ShowText $false -DefaultText 'yes'
    if ('yes' -ne $confirm){
        return
    }
    Get-ChildItem -Path "$softwareBase" | Remove-Item -Recurse -Force
    Remove-Item -Path "$softwareBase" -Force -Recurse
    Set-Enable
}


# ---------------------------------------------------------------------
# Action when after a click on the systray icon
# ---------------------------------------------------------------------
$Main_Tool_Icon.Add_Click({                    
    If ($_.Button -eq [Windows.Forms.MouseButtons]::Left) {
        $Main_Tool_Icon.GetType().GetMethod("ShowContextMenu",[System.Reflection.BindingFlags]::Instance -bor [System.Reflection.BindingFlags]::NonPublic).Invoke($Main_Tool_Icon,$null)
    }
})

Set-Enable
 


 # When Start is clicked, start stayawake job and get its pid
$Menu_Start.add_Click({
    Run-Server
    Set-Enable
 })

 # When Stop is clicked, kill stay awake job
$Menu_Stop.add_Click({
    Kill-Tree $serverProcess.Id
    $serverProcess=$null
    $timer.Enabled=$false
    Set-Enable
 })

# When Exit is clicked, close everything and kill the PowerShell process
$Menu_Exit.add_Click({
    $Main_Tool_Icon.Visible = $false
    if ( $null -ne $serverProcess){
        Kill-Tree $serverProcess.Id
    }
    $timer.Stop()
    [void][System.Windows.Forms.Application]::Exit()
    #Stop-Process $pid
 })
 
 $Menu_Install.add_Click({
    $url=Show-InputDialog -WindowTitle "Install/Update AvNav" -Message "Select the URL for the update" -DefaultText "$defaultUpdateUrl"
    if ($null -eq $url){
        return
    }
    Run-Installer -url "$url"

 })

 $Menu_Remove.add_Click({
    Remove-Installed
 })

 

# Make PowerShell Disappear
$windowcode = '[DllImport("user32.dll")] public static extern bool ShowWindowAsync(IntPtr hWnd, int nCmdShow);'
$asyncwindow = Add-Type -MemberDefinition $windowcode -name Win32ShowWindowAsync -namespace Win32Functions -PassThru
$null = $asyncwindow::ShowWindowAsync((Get-Process -PID $pid).MainWindowHandle, 0)

 

# Force garbage collection just to start slightly lower RAM usage.
[System.GC]::Collect()

 

# Create an application context for it to all run within.
# This helps with responsiveness, especially when clicking Exit.
$appContext = New-Object System.Windows.Forms.ApplicationContext
[void][System.Windows.Forms.Application]::Run($appContext)
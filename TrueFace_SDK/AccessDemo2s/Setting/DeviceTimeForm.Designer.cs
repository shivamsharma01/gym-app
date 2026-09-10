namespace AccessDemo2s
{
    partial class DeviceTimeForm
    {
        /// <summary>
        /// Required designer variable.
        /// </summary>
        private System.ComponentModel.IContainer components = null;

        /// <summary>
        /// Clean up any resources being used.
        /// </summary>
        /// <param name="disposing">true if managed resources should be disposed; otherwise, false.</param>
        protected override void Dispose(bool disposing)
        {
            if (disposing && (components != null))
            {
                components.Dispose();
            }
            base.Dispose(disposing);
        }

        #region Windows Form Designer generated code

        /// <summary>
        /// Required method for Designer support - do not modify
        /// the contents of this method with the code editor.
        /// </summary>
        private void InitializeComponent()
        {
            this.groupBox1 = new System.Windows.Forms.GroupBox();
            this.btn_SetTime = new System.Windows.Forms.Button();
            this.btn_GetTime = new System.Windows.Forms.Button();
            this.dateTimePicker_devicetime = new System.Windows.Forms.DateTimePicker();
            this.groupBox2 = new System.Windows.Forms.GroupBox();
            this.cmb_timezone = new System.Windows.Forms.ComboBox();
            this.txt_description = new System.Windows.Forms.TextBox();
            this.label5 = new System.Windows.Forms.Label();
            this.label4 = new System.Windows.Forms.Label();
            this.txt_upgradeperiod = new System.Windows.Forms.TextBox();
            this.label2 = new System.Windows.Forms.Label();
            this.txt_port = new System.Windows.Forms.TextBox();
            this.label1 = new System.Windows.Forms.Label();
            this.txt_ip = new System.Windows.Forms.TextBox();
            this.label3 = new System.Windows.Forms.Label();
            this.chb_NTPenable = new System.Windows.Forms.CheckBox();
            this.label7 = new System.Windows.Forms.Label();
            this.btn_SetNTP = new System.Windows.Forms.Button();
            this.btn_GetNTP = new System.Windows.Forms.Button();
            this.groupBox3 = new System.Windows.Forms.GroupBox();
            this.cmb_end_minute = new System.Windows.Forms.ComboBox();
            this.cmb_end_hour = new System.Windows.Forms.ComboBox();
            this.cmb_end_day = new System.Windows.Forms.ComboBox();
            this.cmb_end_month = new System.Windows.Forms.ComboBox();
            this.cmb_end_year = new System.Windows.Forms.ComboBox();
            this.label16 = new System.Windows.Forms.Label();
            this.label17 = new System.Windows.Forms.Label();
            this.label18 = new System.Windows.Forms.Label();
            this.label19 = new System.Windows.Forms.Label();
            this.label20 = new System.Windows.Forms.Label();
            this.cmb_start_minute = new System.Windows.Forms.ComboBox();
            this.cmb_start_hour = new System.Windows.Forms.ComboBox();
            this.cmb_start_day = new System.Windows.Forms.ComboBox();
            this.cmb_start_month = new System.Windows.Forms.ComboBox();
            this.cmb_start_year = new System.Windows.Forms.ComboBox();
            this.label15 = new System.Windows.Forms.Label();
            this.label14 = new System.Windows.Forms.Label();
            this.label13 = new System.Windows.Forms.Label();
            this.label8 = new System.Windows.Forms.Label();
            this.label6 = new System.Windows.Forms.Label();
            this.cmb_endweekday = new System.Windows.Forms.ComboBox();
            this.cmb_endweek = new System.Windows.Forms.ComboBox();
            this.cmb_startweekday = new System.Windows.Forms.ComboBox();
            this.cmb_startweek = new System.Windows.Forms.ComboBox();
            this.cmb_DSTtype = new System.Windows.Forms.ComboBox();
            this.label9 = new System.Windows.Forms.Label();
            this.label10 = new System.Windows.Forms.Label();
            this.label11 = new System.Windows.Forms.Label();
            this.chb_DSTenable = new System.Windows.Forms.CheckBox();
            this.label12 = new System.Windows.Forms.Label();
            this.btn_SetDST = new System.Windows.Forms.Button();
            this.btn_GetDST = new System.Windows.Forms.Button();
            this.groupBox1.SuspendLayout();
            this.groupBox2.SuspendLayout();
            this.groupBox3.SuspendLayout();
            this.SuspendLayout();
            // 
            // groupBox1
            // 
            this.groupBox1.BackColor = System.Drawing.Color.Transparent;
            this.groupBox1.Controls.Add(this.btn_SetTime);
            this.groupBox1.Controls.Add(this.btn_GetTime);
            this.groupBox1.Controls.Add(this.dateTimePicker_devicetime);
            this.groupBox1.Location = new System.Drawing.Point(14, 14);
            this.groupBox1.Margin = new System.Windows.Forms.Padding(5);
            this.groupBox1.Name = "groupBox1";
            this.groupBox1.Padding = new System.Windows.Forms.Padding(5);
            this.groupBox1.Size = new System.Drawing.Size(229, 282);
            this.groupBox1.TabIndex = 8;
            this.groupBox1.TabStop = false;
            this.groupBox1.Text = "Device Time()";
            // 
            // btn_SetTime
            // 
            this.btn_SetTime.Location = new System.Drawing.Point(139, 155);
            this.btn_SetTime.Name = "btn_SetTime";
            this.btn_SetTime.Size = new System.Drawing.Size(87, 27);
            this.btn_SetTime.TabIndex = 3;
            this.btn_SetTime.Text = "Set()";
            this.btn_SetTime.UseVisualStyleBackColor = true;
            this.btn_SetTime.Click += new System.EventHandler(this.btn_SetTime_Click);
            // 
            // btn_GetTime
            // 
            this.btn_GetTime.Location = new System.Drawing.Point(13, 155);
            this.btn_GetTime.Name = "btn_GetTime";
            this.btn_GetTime.Size = new System.Drawing.Size(87, 27);
            this.btn_GetTime.TabIndex = 2;
            this.btn_GetTime.Text = "Get()";
            this.btn_GetTime.UseVisualStyleBackColor = true;
            this.btn_GetTime.Click += new System.EventHandler(this.btn_GetTime_Click);
            // 
            // dateTimePicker_devicetime
            // 
            this.dateTimePicker_devicetime.CustomFormat = "yyyy-MM-dd HH:mm:ss";
            this.dateTimePicker_devicetime.Format = System.Windows.Forms.DateTimePickerFormat.Custom;
            this.dateTimePicker_devicetime.Location = new System.Drawing.Point(32, 101);
            this.dateTimePicker_devicetime.Name = "dateTimePicker_devicetime";
            this.dateTimePicker_devicetime.Size = new System.Drawing.Size(175, 23);
            this.dateTimePicker_devicetime.TabIndex = 0;
            // 
            // groupBox2
            // 
            this.groupBox2.BackColor = System.Drawing.Color.Transparent;
            this.groupBox2.Controls.Add(this.cmb_timezone);
            this.groupBox2.Controls.Add(this.txt_description);
            this.groupBox2.Controls.Add(this.label5);
            this.groupBox2.Controls.Add(this.label4);
            this.groupBox2.Controls.Add(this.txt_upgradeperiod);
            this.groupBox2.Controls.Add(this.label2);
            this.groupBox2.Controls.Add(this.txt_port);
            this.groupBox2.Controls.Add(this.label1);
            this.groupBox2.Controls.Add(this.txt_ip);
            this.groupBox2.Controls.Add(this.label3);
            this.groupBox2.Controls.Add(this.chb_NTPenable);
            this.groupBox2.Controls.Add(this.label7);
            this.groupBox2.Controls.Add(this.btn_SetNTP);
            this.groupBox2.Controls.Add(this.btn_GetNTP);
            this.groupBox2.Location = new System.Drawing.Point(253, 14);
            this.groupBox2.Margin = new System.Windows.Forms.Padding(5);
            this.groupBox2.Name = "groupBox2";
            this.groupBox2.Padding = new System.Windows.Forms.Padding(5);
            this.groupBox2.Size = new System.Drawing.Size(329, 282);
            this.groupBox2.TabIndex = 9;
            this.groupBox2.TabStop = false;
            this.groupBox2.Text = "NTP config()";
            // 
            // cmb_timezone
            // 
            this.cmb_timezone.DropDownStyle = System.Windows.Forms.ComboBoxStyle.DropDownList;
            this.cmb_timezone.FormattingEnabled = true;
            this.cmb_timezone.Items.AddRange(new object[] {
            "GMT+00:00",
            "GMT+01:00",
            "GMT+02:00",
            "GMT+03:00",
            "GMT+03:30",
            "GMT+04:00",
            "GMT+04:30",
            "GMT+05:00",
            "GMT+05:30",
            "GMT+05:45",
            "GMT+06:00",
            "GMT+06:30",
            "GMT+07:00",
            "GMT+08:00",
            "GMT+09:00",
            "GMT+09:30",
            "GMT+10:00",
            "GMT+11:00",
            "GMT+12:00",
            "GMT+13:00",
            "GMT-01:00",
            "GMT-02:00",
            "GMT-03:00",
            "GMT-03:30",
            "GMT-04:00",
            "GMT-05:00",
            "GMT-06:00",
            "GMT-07:00",
            "GMT-08:00",
            "GMT-09:00",
            "GMT-10:00",
            "GMT-11:00",
            "GMT-12:00",
            "GMT-4:30",
            "GMT+10:30",
            "GMT+14:00",
            "GMT-09:30",
            "GMT+08:30",
            "GMT+08:45",
            "GMT+12:45"});
            this.cmb_timezone.Location = new System.Drawing.Point(197, 160);
            this.cmb_timezone.Name = "cmb_timezone";
            this.cmb_timezone.Size = new System.Drawing.Size(120, 22);
            this.cmb_timezone.TabIndex = 32;
            // 
            // txt_description
            // 
            this.txt_description.Location = new System.Drawing.Point(197, 198);
            this.txt_description.Margin = new System.Windows.Forms.Padding(4);
            this.txt_description.Name = "txt_description";
            this.txt_description.Size = new System.Drawing.Size(120, 23);
            this.txt_description.TabIndex = 31;
            // 
            // label5
            // 
            this.label5.Location = new System.Drawing.Point(33, 188);
            this.label5.Margin = new System.Windows.Forms.Padding(4, 0, 4, 0);
            this.label5.Name = "label5";
            this.label5.Size = new System.Drawing.Size(156, 37);
            this.label5.TabIndex = 30;
            this.label5.Text = "Timezone description ():";
            // 
            // label4
            // 
            this.label4.AutoSize = true;
            this.label4.Location = new System.Drawing.Point(70, 163);
            this.label4.Margin = new System.Windows.Forms.Padding(4, 0, 4, 0);
            this.label4.Name = "label4";
            this.label4.Size = new System.Drawing.Size(119, 14);
            this.label4.TabIndex = 28;
            this.label4.Text = "Time zone():";
            // 
            // txt_upgradeperiod
            // 
            this.txt_upgradeperiod.Location = new System.Drawing.Point(197, 127);
            this.txt_upgradeperiod.Margin = new System.Windows.Forms.Padding(4);
            this.txt_upgradeperiod.Name = "txt_upgradeperiod";
            this.txt_upgradeperiod.Size = new System.Drawing.Size(120, 23);
            this.txt_upgradeperiod.TabIndex = 27;
            // 
            // label2
            // 
            this.label2.AutoSize = true;
            this.label2.Location = new System.Drawing.Point(21, 130);
            this.label2.Margin = new System.Windows.Forms.Padding(4, 0, 4, 0);
            this.label2.Name = "label2";
            this.label2.Size = new System.Drawing.Size(168, 14);
            this.label2.TabIndex = 26;
            this.label2.Text = "UpdatePeriod():";
            // 
            // txt_port
            // 
            this.txt_port.Location = new System.Drawing.Point(197, 94);
            this.txt_port.Margin = new System.Windows.Forms.Padding(4);
            this.txt_port.Name = "txt_port";
            this.txt_port.Size = new System.Drawing.Size(120, 23);
            this.txt_port.TabIndex = 25;
            // 
            // label1
            // 
            this.label1.AutoSize = true;
            this.label1.Location = new System.Drawing.Point(105, 97);
            this.label1.Margin = new System.Windows.Forms.Padding(4, 0, 4, 0);
            this.label1.Name = "label1";
            this.label1.Size = new System.Drawing.Size(84, 14);
            this.label1.TabIndex = 24;
            this.label1.Text = "Port():";
            // 
            // txt_ip
            // 
            this.txt_ip.Location = new System.Drawing.Point(197, 61);
            this.txt_ip.Margin = new System.Windows.Forms.Padding(4);
            this.txt_ip.Name = "txt_ip";
            this.txt_ip.Size = new System.Drawing.Size(120, 23);
            this.txt_ip.TabIndex = 23;
            // 
            // label3
            // 
            this.label3.AutoSize = true;
            this.label3.Location = new System.Drawing.Point(14, 64);
            this.label3.Margin = new System.Windows.Forms.Padding(4, 0, 4, 0);
            this.label3.Name = "label3";
            this.label3.Size = new System.Drawing.Size(175, 14);
            this.label3.TabIndex = 22;
            this.label3.Text = "IP or Net(IP):";
            // 
            // chb_NTPenable
            // 
            this.chb_NTPenable.AutoSize = true;
            this.chb_NTPenable.Location = new System.Drawing.Point(197, 31);
            this.chb_NTPenable.Name = "chb_NTPenable";
            this.chb_NTPenable.Size = new System.Drawing.Size(15, 14);
            this.chb_NTPenable.TabIndex = 21;
            this.chb_NTPenable.UseVisualStyleBackColor = true;
            // 
            // label7
            // 
            this.label7.AutoSize = true;
            this.label7.Location = new System.Drawing.Point(91, 31);
            this.label7.Margin = new System.Windows.Forms.Padding(4, 0, 4, 0);
            this.label7.Name = "label7";
            this.label7.Size = new System.Drawing.Size(98, 14);
            this.label7.TabIndex = 20;
            this.label7.Text = "Enable():";
            // 
            // btn_SetNTP
            // 
            this.btn_SetNTP.Location = new System.Drawing.Point(197, 238);
            this.btn_SetNTP.Name = "btn_SetNTP";
            this.btn_SetNTP.Size = new System.Drawing.Size(87, 27);
            this.btn_SetNTP.TabIndex = 3;
            this.btn_SetNTP.Text = "Set()";
            this.btn_SetNTP.UseVisualStyleBackColor = true;
            this.btn_SetNTP.Click += new System.EventHandler(this.btn_SetNTP_Click);
            // 
            // btn_GetNTP
            // 
            this.btn_GetNTP.Location = new System.Drawing.Point(77, 238);
            this.btn_GetNTP.Name = "btn_GetNTP";
            this.btn_GetNTP.Size = new System.Drawing.Size(87, 27);
            this.btn_GetNTP.TabIndex = 2;
            this.btn_GetNTP.Text = "Get()";
            this.btn_GetNTP.UseVisualStyleBackColor = true;
            this.btn_GetNTP.Click += new System.EventHandler(this.btn_GetNTP_Click);
            // 
            // groupBox3
            // 
            this.groupBox3.BackColor = System.Drawing.Color.Transparent;
            this.groupBox3.Controls.Add(this.cmb_end_minute);
            this.groupBox3.Controls.Add(this.cmb_end_hour);
            this.groupBox3.Controls.Add(this.cmb_end_day);
            this.groupBox3.Controls.Add(this.cmb_end_month);
            this.groupBox3.Controls.Add(this.cmb_end_year);
            this.groupBox3.Controls.Add(this.label16);
            this.groupBox3.Controls.Add(this.label17);
            this.groupBox3.Controls.Add(this.label18);
            this.groupBox3.Controls.Add(this.label19);
            this.groupBox3.Controls.Add(this.label20);
            this.groupBox3.Controls.Add(this.cmb_start_minute);
            this.groupBox3.Controls.Add(this.cmb_start_hour);
            this.groupBox3.Controls.Add(this.cmb_start_day);
            this.groupBox3.Controls.Add(this.cmb_start_month);
            this.groupBox3.Controls.Add(this.cmb_start_year);
            this.groupBox3.Controls.Add(this.label15);
            this.groupBox3.Controls.Add(this.label14);
            this.groupBox3.Controls.Add(this.label13);
            this.groupBox3.Controls.Add(this.label8);
            this.groupBox3.Controls.Add(this.label6);
            this.groupBox3.Controls.Add(this.cmb_endweekday);
            this.groupBox3.Controls.Add(this.cmb_endweek);
            this.groupBox3.Controls.Add(this.cmb_startweekday);
            this.groupBox3.Controls.Add(this.cmb_startweek);
            this.groupBox3.Controls.Add(this.cmb_DSTtype);
            this.groupBox3.Controls.Add(this.label9);
            this.groupBox3.Controls.Add(this.label10);
            this.groupBox3.Controls.Add(this.label11);
            this.groupBox3.Controls.Add(this.chb_DSTenable);
            this.groupBox3.Controls.Add(this.label12);
            this.groupBox3.Controls.Add(this.btn_SetDST);
            this.groupBox3.Controls.Add(this.btn_GetDST);
            this.groupBox3.Location = new System.Drawing.Point(14, 306);
            this.groupBox3.Margin = new System.Windows.Forms.Padding(5);
            this.groupBox3.Name = "groupBox3";
            this.groupBox3.Padding = new System.Windows.Forms.Padding(5);
            this.groupBox3.Size = new System.Drawing.Size(568, 361);
            this.groupBox3.TabIndex = 32;
            this.groupBox3.TabStop = false;
            this.groupBox3.Text = "DST config()";
            // 
            // cmb_end_minute
            // 
            this.cmb_end_minute.DropDownStyle = System.Windows.Forms.ComboBoxStyle.DropDownList;
            this.cmb_end_minute.FormattingEnabled = true;
            this.cmb_end_minute.Items.AddRange(new object[] {
            "58",
            "59"});
            this.cmb_end_minute.Location = new System.Drawing.Point(487, 229);
            this.cmb_end_minute.Name = "cmb_end_minute";
            this.cmb_end_minute.Size = new System.Drawing.Size(60, 22);
            this.cmb_end_minute.TabIndex = 58;
            // 
            // cmb_end_hour
            // 
            this.cmb_end_hour.DropDownStyle = System.Windows.Forms.ComboBoxStyle.DropDownList;
            this.cmb_end_hour.FormattingEnabled = true;
            this.cmb_end_hour.Items.AddRange(new object[] {
            "23",
            "24"});
            this.cmb_end_hour.Location = new System.Drawing.Point(402, 229);
            this.cmb_end_hour.Name = "cmb_end_hour";
            this.cmb_end_hour.Size = new System.Drawing.Size(60, 22);
            this.cmb_end_hour.TabIndex = 57;
            // 
            // cmb_end_day
            // 
            this.cmb_end_day.DropDownStyle = System.Windows.Forms.ComboBoxStyle.DropDownList;
            this.cmb_end_day.Enabled = false;
            this.cmb_end_day.FormattingEnabled = true;
            this.cmb_end_day.Location = new System.Drawing.Point(328, 229);
            this.cmb_end_day.Name = "cmb_end_day";
            this.cmb_end_day.Size = new System.Drawing.Size(60, 22);
            this.cmb_end_day.TabIndex = 56;
            // 
            // cmb_end_month
            // 
            this.cmb_end_month.DropDownStyle = System.Windows.Forms.ComboBoxStyle.DropDownList;
            this.cmb_end_month.FormattingEnabled = true;
            this.cmb_end_month.Items.AddRange(new object[] {
            "11",
            "12"});
            this.cmb_end_month.Location = new System.Drawing.Point(250, 229);
            this.cmb_end_month.Name = "cmb_end_month";
            this.cmb_end_month.Size = new System.Drawing.Size(60, 22);
            this.cmb_end_month.TabIndex = 55;
            this.cmb_end_month.SelectedIndexChanged += new System.EventHandler(this.cmb_end_month_SelectedIndexChanged);
            // 
            // cmb_end_year
            // 
            this.cmb_end_year.DropDownStyle = System.Windows.Forms.ComboBoxStyle.DropDownList;
            this.cmb_end_year.FormattingEnabled = true;
            this.cmb_end_year.Items.AddRange(new object[] {
            "2000",
            "2001"});
            this.cmb_end_year.Location = new System.Drawing.Point(158, 229);
            this.cmb_end_year.Name = "cmb_end_year";
            this.cmb_end_year.Size = new System.Drawing.Size(80, 22);
            this.cmb_end_year.TabIndex = 54;
            // 
            // label16
            // 
            this.label16.AutoSize = true;
            this.label16.Location = new System.Drawing.Point(479, 203);
            this.label16.Margin = new System.Windows.Forms.Padding(4, 0, 4, 0);
            this.label16.Name = "label16";
            this.label16.Size = new System.Drawing.Size(77, 14);
            this.label16.TabIndex = 53;
            this.label16.Text = "Minute()";
            // 
            // label17
            // 
            this.label17.AutoSize = true;
            this.label17.Location = new System.Drawing.Point(401, 203);
            this.label17.Margin = new System.Windows.Forms.Padding(4, 0, 4, 0);
            this.label17.Name = "label17";
            this.label17.Size = new System.Drawing.Size(63, 14);
            this.label17.TabIndex = 52;
            this.label17.Text = "Hour()";
            // 
            // label18
            // 
            this.label18.AutoSize = true;
            this.label18.Location = new System.Drawing.Point(330, 203);
            this.label18.Margin = new System.Windows.Forms.Padding(4, 0, 4, 0);
            this.label18.Name = "label18";
            this.label18.Size = new System.Drawing.Size(56, 14);
            this.label18.TabIndex = 51;
            this.label18.Text = "Day()";
            // 
            // label19
            // 
            this.label19.AutoSize = true;
            this.label19.Location = new System.Drawing.Point(245, 203);
            this.label19.Margin = new System.Windows.Forms.Padding(4, 0, 4, 0);
            this.label19.Name = "label19";
            this.label19.Size = new System.Drawing.Size(70, 14);
            this.label19.TabIndex = 50;
            this.label19.Text = "Month()";
            // 
            // label20
            // 
            this.label20.AutoSize = true;
            this.label20.Location = new System.Drawing.Point(167, 203);
            this.label20.Margin = new System.Windows.Forms.Padding(4, 0, 4, 0);
            this.label20.Name = "label20";
            this.label20.Size = new System.Drawing.Size(63, 14);
            this.label20.TabIndex = 49;
            this.label20.Text = "Year()";
            // 
            // cmb_start_minute
            // 
            this.cmb_start_minute.DropDownStyle = System.Windows.Forms.ComboBoxStyle.DropDownList;
            this.cmb_start_minute.FormattingEnabled = true;
            this.cmb_start_minute.Items.AddRange(new object[] {
            "58",
            "59"});
            this.cmb_start_minute.Location = new System.Drawing.Point(487, 123);
            this.cmb_start_minute.Name = "cmb_start_minute";
            this.cmb_start_minute.Size = new System.Drawing.Size(60, 22);
            this.cmb_start_minute.TabIndex = 47;
            // 
            // cmb_start_hour
            // 
            this.cmb_start_hour.DropDownStyle = System.Windows.Forms.ComboBoxStyle.DropDownList;
            this.cmb_start_hour.FormattingEnabled = true;
            this.cmb_start_hour.Items.AddRange(new object[] {
            "23",
            "24"});
            this.cmb_start_hour.Location = new System.Drawing.Point(402, 123);
            this.cmb_start_hour.Name = "cmb_start_hour";
            this.cmb_start_hour.Size = new System.Drawing.Size(60, 22);
            this.cmb_start_hour.TabIndex = 46;
            // 
            // cmb_start_day
            // 
            this.cmb_start_day.DropDownStyle = System.Windows.Forms.ComboBoxStyle.DropDownList;
            this.cmb_start_day.Enabled = false;
            this.cmb_start_day.FormattingEnabled = true;
            this.cmb_start_day.Location = new System.Drawing.Point(328, 123);
            this.cmb_start_day.Name = "cmb_start_day";
            this.cmb_start_day.Size = new System.Drawing.Size(60, 22);
            this.cmb_start_day.TabIndex = 45;
            // 
            // cmb_start_month
            // 
            this.cmb_start_month.DropDownStyle = System.Windows.Forms.ComboBoxStyle.DropDownList;
            this.cmb_start_month.FormattingEnabled = true;
            this.cmb_start_month.Items.AddRange(new object[] {
            "11",
            "12"});
            this.cmb_start_month.Location = new System.Drawing.Point(250, 123);
            this.cmb_start_month.Name = "cmb_start_month";
            this.cmb_start_month.Size = new System.Drawing.Size(60, 22);
            this.cmb_start_month.TabIndex = 44;
            this.cmb_start_month.SelectedIndexChanged += new System.EventHandler(this.cmb_start_month_SelectedIndexChanged);
            // 
            // cmb_start_year
            // 
            this.cmb_start_year.DropDownStyle = System.Windows.Forms.ComboBoxStyle.DropDownList;
            this.cmb_start_year.FormattingEnabled = true;
            this.cmb_start_year.Items.AddRange(new object[] {
            "2000",
            "2001"});
            this.cmb_start_year.Location = new System.Drawing.Point(158, 123);
            this.cmb_start_year.Name = "cmb_start_year";
            this.cmb_start_year.Size = new System.Drawing.Size(80, 22);
            this.cmb_start_year.TabIndex = 43;
            // 
            // label15
            // 
            this.label15.AutoSize = true;
            this.label15.Location = new System.Drawing.Point(479, 97);
            this.label15.Margin = new System.Windows.Forms.Padding(4, 0, 4, 0);
            this.label15.Name = "label15";
            this.label15.Size = new System.Drawing.Size(77, 14);
            this.label15.TabIndex = 42;
            this.label15.Text = "Minute()";
            // 
            // label14
            // 
            this.label14.AutoSize = true;
            this.label14.Location = new System.Drawing.Point(401, 97);
            this.label14.Margin = new System.Windows.Forms.Padding(4, 0, 4, 0);
            this.label14.Name = "label14";
            this.label14.Size = new System.Drawing.Size(63, 14);
            this.label14.TabIndex = 41;
            this.label14.Text = "Hour()";
            // 
            // label13
            // 
            this.label13.AutoSize = true;
            this.label13.Location = new System.Drawing.Point(330, 97);
            this.label13.Margin = new System.Windows.Forms.Padding(4, 0, 4, 0);
            this.label13.Name = "label13";
            this.label13.Size = new System.Drawing.Size(56, 14);
            this.label13.TabIndex = 40;
            this.label13.Text = "Day()";
            // 
            // label8
            // 
            this.label8.AutoSize = true;
            this.label8.Location = new System.Drawing.Point(245, 97);
            this.label8.Margin = new System.Windows.Forms.Padding(4, 0, 4, 0);
            this.label8.Name = "label8";
            this.label8.Size = new System.Drawing.Size(70, 14);
            this.label8.TabIndex = 39;
            this.label8.Text = "Month()";
            // 
            // label6
            // 
            this.label6.AutoSize = true;
            this.label6.Location = new System.Drawing.Point(167, 97);
            this.label6.Margin = new System.Windows.Forms.Padding(4, 0, 4, 0);
            this.label6.Name = "label6";
            this.label6.Size = new System.Drawing.Size(63, 14);
            this.label6.TabIndex = 38;
            this.label6.Text = "Year()";
            // 
            // cmb_endweekday
            // 
            this.cmb_endweekday.DropDownStyle = System.Windows.Forms.ComboBoxStyle.DropDownList;
            this.cmb_endweekday.FormattingEnabled = true;
            this.cmb_endweekday.Items.AddRange(new object[] {
            "Sun.()",
            "Mon.() ",
            "Tue.() ",
            "Wed.() ",
            "Thu.() ",
            "Fri.() ",
            "Sat.() "});
            this.cmb_endweekday.Location = new System.Drawing.Point(387, 265);
            this.cmb_endweekday.Name = "cmb_endweekday";
            this.cmb_endweekday.Size = new System.Drawing.Size(160, 22);
            this.cmb_endweekday.TabIndex = 37;
            // 
            // cmb_endweek
            // 
            this.cmb_endweek.DropDownStyle = System.Windows.Forms.ComboBoxStyle.DropDownList;
            this.cmb_endweek.FormattingEnabled = true;
            this.cmb_endweek.Items.AddRange(new object[] {
            "Last Week()",
            "First Week()",
            "Second Week()",
            "Third Week()",
            "Fourth Week()"});
            this.cmb_endweek.Location = new System.Drawing.Point(158, 265);
            this.cmb_endweek.Name = "cmb_endweek";
            this.cmb_endweek.Size = new System.Drawing.Size(223, 22);
            this.cmb_endweek.TabIndex = 36;
            // 
            // cmb_startweekday
            // 
            this.cmb_startweekday.DropDownStyle = System.Windows.Forms.ComboBoxStyle.DropDownList;
            this.cmb_startweekday.FormattingEnabled = true;
            this.cmb_startweekday.Items.AddRange(new object[] {
            "Sun.()",
            "Mon.() ",
            "Tue.() ",
            "Wed.() ",
            "Thu.() ",
            "Fri.() ",
            "Sat.() "});
            this.cmb_startweekday.Location = new System.Drawing.Point(387, 160);
            this.cmb_startweekday.Name = "cmb_startweekday";
            this.cmb_startweekday.Size = new System.Drawing.Size(160, 22);
            this.cmb_startweekday.TabIndex = 35;
            // 
            // cmb_startweek
            // 
            this.cmb_startweek.DropDownStyle = System.Windows.Forms.ComboBoxStyle.DropDownList;
            this.cmb_startweek.FormattingEnabled = true;
            this.cmb_startweek.Items.AddRange(new object[] {
            "Last Week()",
            "First Week()",
            "Second Week()",
            "Third Week()",
            "Fourth Week()"});
            this.cmb_startweek.Location = new System.Drawing.Point(158, 160);
            this.cmb_startweek.Name = "cmb_startweek";
            this.cmb_startweek.Size = new System.Drawing.Size(223, 22);
            this.cmb_startweek.TabIndex = 34;
            // 
            // cmb_DSTtype
            // 
            this.cmb_DSTtype.DropDownStyle = System.Windows.Forms.ComboBoxStyle.DropDownList;
            this.cmb_DSTtype.FormattingEnabled = true;
            this.cmb_DSTtype.Items.AddRange(new object[] {
            "By Date()",
            "By Week()"});
            this.cmb_DSTtype.Location = new System.Drawing.Point(158, 59);
            this.cmb_DSTtype.Name = "cmb_DSTtype";
            this.cmb_DSTtype.Size = new System.Drawing.Size(144, 22);
            this.cmb_DSTtype.TabIndex = 33;
            this.cmb_DSTtype.SelectedIndexChanged += new System.EventHandler(this.cmb_DSTtype_SelectedIndexChanged);
            // 
            // label9
            // 
            this.label9.AutoSize = true;
            this.label9.Location = new System.Drawing.Point(19, 203);
            this.label9.Margin = new System.Windows.Forms.Padding(4, 0, 4, 0);
            this.label9.Name = "label9";
            this.label9.Size = new System.Drawing.Size(140, 14);
            this.label9.TabIndex = 26;
            this.label9.Text = "DSTEnd():";
            // 
            // label10
            // 
            this.label10.AutoSize = true;
            this.label10.Location = new System.Drawing.Point(5, 97);
            this.label10.Margin = new System.Windows.Forms.Padding(4, 0, 4, 0);
            this.label10.Name = "label10";
            this.label10.Size = new System.Drawing.Size(154, 14);
            this.label10.TabIndex = 24;
            this.label10.Text = "DSTStart():";
            // 
            // label11
            // 
            this.label11.AutoSize = true;
            this.label11.Location = new System.Drawing.Point(12, 62);
            this.label11.Margin = new System.Windows.Forms.Padding(4, 0, 4, 0);
            this.label11.Name = "label11";
            this.label11.Size = new System.Drawing.Size(147, 14);
            this.label11.TabIndex = 22;
            this.label11.Text = "DSTType():";
            // 
            // chb_DSTenable
            // 
            this.chb_DSTenable.AutoSize = true;
            this.chb_DSTenable.Location = new System.Drawing.Point(158, 35);
            this.chb_DSTenable.Name = "chb_DSTenable";
            this.chb_DSTenable.Size = new System.Drawing.Size(15, 14);
            this.chb_DSTenable.TabIndex = 21;
            this.chb_DSTenable.UseVisualStyleBackColor = true;
            // 
            // label12
            // 
            this.label12.AutoSize = true;
            this.label12.Location = new System.Drawing.Point(61, 35);
            this.label12.Margin = new System.Windows.Forms.Padding(4, 0, 4, 0);
            this.label12.Name = "label12";
            this.label12.Size = new System.Drawing.Size(98, 14);
            this.label12.TabIndex = 20;
            this.label12.Text = "Enable():";
            // 
            // btn_SetDST
            // 
            this.btn_SetDST.Location = new System.Drawing.Point(312, 310);
            this.btn_SetDST.Name = "btn_SetDST";
            this.btn_SetDST.Size = new System.Drawing.Size(100, 27);
            this.btn_SetDST.TabIndex = 3;
            this.btn_SetDST.Text = "Set()";
            this.btn_SetDST.UseVisualStyleBackColor = true;
            this.btn_SetDST.Click += new System.EventHandler(this.btn_SetDST_Click);
            // 
            // btn_GetDST
            // 
            this.btn_GetDST.Location = new System.Drawing.Point(158, 310);
            this.btn_GetDST.Name = "btn_GetDST";
            this.btn_GetDST.Size = new System.Drawing.Size(100, 27);
            this.btn_GetDST.TabIndex = 2;
            this.btn_GetDST.Text = "Get()";
            this.btn_GetDST.UseVisualStyleBackColor = true;
            this.btn_GetDST.Click += new System.EventHandler(this.btn_GetDST_Click);
            // 
            // DeviceTimeForm
            // 
            this.AutoScaleDimensions = new System.Drawing.SizeF(7F, 14F);
            this.AutoScaleMode = System.Windows.Forms.AutoScaleMode.Font;
            this.ClientSize = new System.Drawing.Size(594, 681);
            this.Controls.Add(this.groupBox3);
            this.Controls.Add(this.groupBox2);
            this.Controls.Add(this.groupBox1);
            this.Font = new System.Drawing.Font("", 10.5F, System.Drawing.FontStyle.Regular, System.Drawing.GraphicsUnit.Point, ((byte)(134)));
            this.FormBorderStyle = System.Windows.Forms.FormBorderStyle.FixedSingle;
            this.MaximizeBox = false;
            this.Name = "DeviceTimeForm";
            this.StartPosition = System.Windows.Forms.FormStartPosition.CenterParent;
            this.Text = "Device Time()";
            this.Load += new System.EventHandler(this.DeviceTimeForm_Load);
            this.groupBox1.ResumeLayout(false);
            this.groupBox2.ResumeLayout(false);
            this.groupBox2.PerformLayout();
            this.groupBox3.ResumeLayout(false);
            this.groupBox3.PerformLayout();
            this.ResumeLayout(false);

        }

        #endregion

        private System.Windows.Forms.GroupBox groupBox1;
        private System.Windows.Forms.Button btn_SetTime;
        private System.Windows.Forms.Button btn_GetTime;
        private System.Windows.Forms.DateTimePicker dateTimePicker_devicetime;
        private System.Windows.Forms.GroupBox groupBox2;
        private System.Windows.Forms.Button btn_SetNTP;
        private System.Windows.Forms.Button btn_GetNTP;
        private System.Windows.Forms.CheckBox chb_NTPenable;
        private System.Windows.Forms.Label label7;
        private System.Windows.Forms.TextBox txt_description;
        private System.Windows.Forms.Label label5;
        private System.Windows.Forms.Label label4;
        private System.Windows.Forms.TextBox txt_upgradeperiod;
        private System.Windows.Forms.Label label2;
        private System.Windows.Forms.TextBox txt_port;
        private System.Windows.Forms.Label label1;
        private System.Windows.Forms.TextBox txt_ip;
        private System.Windows.Forms.Label label3;
        private System.Windows.Forms.GroupBox groupBox3;
        private System.Windows.Forms.Label label9;
        private System.Windows.Forms.Label label10;
        private System.Windows.Forms.Label label11;
        private System.Windows.Forms.CheckBox chb_DSTenable;
        private System.Windows.Forms.Label label12;
        private System.Windows.Forms.Button btn_SetDST;
        private System.Windows.Forms.Button btn_GetDST;
        private System.Windows.Forms.ComboBox cmb_timezone;
        private System.Windows.Forms.ComboBox cmb_DSTtype;
        private System.Windows.Forms.ComboBox cmb_endweekday;
        private System.Windows.Forms.ComboBox cmb_endweek;
        private System.Windows.Forms.ComboBox cmb_startweekday;
        private System.Windows.Forms.ComboBox cmb_startweek;
        private System.Windows.Forms.ComboBox cmb_start_minute;
        private System.Windows.Forms.ComboBox cmb_start_hour;
        private System.Windows.Forms.ComboBox cmb_start_day;
        private System.Windows.Forms.ComboBox cmb_start_month;
        private System.Windows.Forms.ComboBox cmb_start_year;
        private System.Windows.Forms.Label label15;
        private System.Windows.Forms.Label label14;
        private System.Windows.Forms.Label label13;
        private System.Windows.Forms.Label label8;
        private System.Windows.Forms.Label label6;
        private System.Windows.Forms.ComboBox cmb_end_minute;
        private System.Windows.Forms.ComboBox cmb_end_hour;
        private System.Windows.Forms.ComboBox cmb_end_day;
        private System.Windows.Forms.ComboBox cmb_end_month;
        private System.Windows.Forms.ComboBox cmb_end_year;
        private System.Windows.Forms.Label label16;
        private System.Windows.Forms.Label label17;
        private System.Windows.Forms.Label label18;
        private System.Windows.Forms.Label label19;
        private System.Windows.Forms.Label label20;
    }
}
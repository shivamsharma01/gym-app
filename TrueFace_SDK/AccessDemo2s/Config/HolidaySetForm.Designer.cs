namespace AccessDemo2s
{
    partial class HolidaySetForm
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
            this.cmb_Index = new System.Windows.Forms.ComboBox();
            this.label1 = new System.Windows.Forms.Label();
            this.chb_Enable = new System.Windows.Forms.CheckBox();
            this.label11 = new System.Windows.Forms.Label();
            this.txt_Name = new System.Windows.Forms.TextBox();
            this.label3 = new System.Windows.Forms.Label();
            this.groupBox1 = new System.Windows.Forms.GroupBox();
            this.btn_GetGroup = new System.Windows.Forms.Button();
            this.btn_SetGroup = new System.Windows.Forms.Button();
            this.btn_RemoveGroup = new System.Windows.Forms.Button();
            this.btn_ModifyGroup = new System.Windows.Forms.Button();
            this.btn_AddGroup = new System.Windows.Forms.Button();
            this.listView_Group = new System.Windows.Forms.ListView();
            this.columnHeader2 = ((System.Windows.Forms.ColumnHeader)(new System.Windows.Forms.ColumnHeader()));
            this.columnHeader3 = ((System.Windows.Forms.ColumnHeader)(new System.Windows.Forms.ColumnHeader()));
            this.columnHeader4 = ((System.Windows.Forms.ColumnHeader)(new System.Windows.Forms.ColumnHeader()));
            this.columnHeader5 = ((System.Windows.Forms.ColumnHeader)(new System.Windows.Forms.ColumnHeader()));
            this.groupBox2 = new System.Windows.Forms.GroupBox();
            this.btn_Doors = new System.Windows.Forms.Button();
            this.label2 = new System.Windows.Forms.Label();
            this.groupBox3 = new System.Windows.Forms.GroupBox();
            this.endtime4 = new System.Windows.Forms.DateTimePicker();
            this.starttime4 = new System.Windows.Forms.DateTimePicker();
            this.label8 = new System.Windows.Forms.Label();
            this.endtime2 = new System.Windows.Forms.DateTimePicker();
            this.starttime2 = new System.Windows.Forms.DateTimePicker();
            this.label9 = new System.Windows.Forms.Label();
            this.endtime3 = new System.Windows.Forms.DateTimePicker();
            this.starttime3 = new System.Windows.Forms.DateTimePicker();
            this.label10 = new System.Windows.Forms.Label();
            this.label12 = new System.Windows.Forms.Label();
            this.label13 = new System.Windows.Forms.Label();
            this.endtime1 = new System.Windows.Forms.DateTimePicker();
            this.starttime1 = new System.Windows.Forms.DateTimePicker();
            this.label14 = new System.Windows.Forms.Label();
            this.txt_GroupNum = new System.Windows.Forms.TextBox();
            this.label7 = new System.Windows.Forms.Label();
            this.btn_GetSchedule = new System.Windows.Forms.Button();
            this.btn_SetSchedule = new System.Windows.Forms.Button();
            this.txt_ScheduleName = new System.Windows.Forms.TextBox();
            this.label4 = new System.Windows.Forms.Label();
            this.label5 = new System.Windows.Forms.Label();
            this.cmb_ScheduleGroup = new System.Windows.Forms.ComboBox();
            this.chb_ScheduleEnable = new System.Windows.Forms.CheckBox();
            this.label6 = new System.Windows.Forms.Label();
            this.groupBox1.SuspendLayout();
            this.groupBox2.SuspendLayout();
            this.groupBox3.SuspendLayout();
            this.SuspendLayout();
            // 
            // cmb_Index
            // 
            this.cmb_Index.DropDownStyle = System.Windows.Forms.ComboBoxStyle.DropDownList;
            this.cmb_Index.FormattingEnabled = true;
            this.cmb_Index.Items.AddRange(new object[] {
            ""});
            this.cmb_Index.Location = new System.Drawing.Point(120, 41);
            this.cmb_Index.Name = "cmb_Index";
            this.cmb_Index.Size = new System.Drawing.Size(96, 22);
            this.cmb_Index.TabIndex = 119;
            this.cmb_Index.SelectedIndexChanged += new System.EventHandler(this.cmb_Index_SelectedIndexChanged);
            // 
            // label1
            // 
            this.label1.AutoSize = true;
            this.label1.Location = new System.Drawing.Point(25, 44);
            this.label1.Margin = new System.Windows.Forms.Padding(5, 0, 5, 0);
            this.label1.Name = "label1";
            this.label1.Size = new System.Drawing.Size(91, 14);
            this.label1.TabIndex = 118;
            this.label1.Text = "Index():";
            this.label1.TextAlign = System.Drawing.ContentAlignment.MiddleRight;
            // 
            // chb_Enable
            // 
            this.chb_Enable.AutoSize = true;
            this.chb_Enable.Font = new System.Drawing.Font("", 5.25F, System.Drawing.FontStyle.Regular, System.Drawing.GraphicsUnit.Point, ((byte)(134)));
            this.chb_Enable.Location = new System.Drawing.Point(120, 84);
            this.chb_Enable.Name = "chb_Enable";
            this.chb_Enable.Size = new System.Drawing.Size(15, 14);
            this.chb_Enable.TabIndex = 125;
            this.chb_Enable.UseVisualStyleBackColor = true;
            // 
            // label11
            // 
            this.label11.AutoSize = true;
            this.label11.Location = new System.Drawing.Point(16, 84);
            this.label11.Name = "label11";
            this.label11.Size = new System.Drawing.Size(98, 14);
            this.label11.TabIndex = 124;
            this.label11.Text = "Enable():";
            // 
            // txt_Name
            // 
            this.txt_Name.Location = new System.Drawing.Point(122, 116);
            this.txt_Name.Margin = new System.Windows.Forms.Padding(4);
            this.txt_Name.MaxLength = 3000;
            this.txt_Name.Name = "txt_Name";
            this.txt_Name.Size = new System.Drawing.Size(276, 23);
            this.txt_Name.TabIndex = 127;
            // 
            // label3
            // 
            this.label3.AutoSize = true;
            this.label3.Location = new System.Drawing.Point(30, 119);
            this.label3.Margin = new System.Windows.Forms.Padding(4, 0, 4, 0);
            this.label3.Name = "label3";
            this.label3.Size = new System.Drawing.Size(84, 14);
            this.label3.TabIndex = 126;
            this.label3.Text = "Name():";
            // 
            // groupBox1
            // 
            this.groupBox1.Controls.Add(this.btn_GetGroup);
            this.groupBox1.Controls.Add(this.btn_SetGroup);
            this.groupBox1.Controls.Add(this.btn_RemoveGroup);
            this.groupBox1.Controls.Add(this.btn_ModifyGroup);
            this.groupBox1.Controls.Add(this.btn_AddGroup);
            this.groupBox1.Controls.Add(this.listView_Group);
            this.groupBox1.Controls.Add(this.txt_Name);
            this.groupBox1.Controls.Add(this.label1);
            this.groupBox1.Controls.Add(this.label3);
            this.groupBox1.Controls.Add(this.cmb_Index);
            this.groupBox1.Controls.Add(this.chb_Enable);
            this.groupBox1.Controls.Add(this.label11);
            this.groupBox1.Location = new System.Drawing.Point(12, 12);
            this.groupBox1.Name = "groupBox1";
            this.groupBox1.Size = new System.Drawing.Size(730, 537);
            this.groupBox1.TabIndex = 128;
            this.groupBox1.TabStop = false;
            this.groupBox1.Text = "SpecialDayGroup()";
            // 
            // btn_GetGroup
            // 
            this.btn_GetGroup.Font = new System.Drawing.Font("", 10.5F, System.Drawing.FontStyle.Regular, System.Drawing.GraphicsUnit.Point, ((byte)(134)));
            this.btn_GetGroup.Location = new System.Drawing.Point(76, 493);
            this.btn_GetGroup.Margin = new System.Windows.Forms.Padding(4);
            this.btn_GetGroup.Name = "btn_GetGroup";
            this.btn_GetGroup.Size = new System.Drawing.Size(140, 24);
            this.btn_GetGroup.TabIndex = 133;
            this.btn_GetGroup.Text = "Get()";
            this.btn_GetGroup.UseVisualStyleBackColor = true;
            this.btn_GetGroup.Click += new System.EventHandler(this.btn_GetGroup_Click);
            // 
            // btn_SetGroup
            // 
            this.btn_SetGroup.Font = new System.Drawing.Font("", 10.5F, System.Drawing.FontStyle.Regular, System.Drawing.GraphicsUnit.Point, ((byte)(134)));
            this.btn_SetGroup.Location = new System.Drawing.Point(315, 493);
            this.btn_SetGroup.Margin = new System.Windows.Forms.Padding(4);
            this.btn_SetGroup.Name = "btn_SetGroup";
            this.btn_SetGroup.Size = new System.Drawing.Size(140, 24);
            this.btn_SetGroup.TabIndex = 132;
            this.btn_SetGroup.Text = "Set()";
            this.btn_SetGroup.UseVisualStyleBackColor = true;
            this.btn_SetGroup.Click += new System.EventHandler(this.btn_SetGroup_Click);
            // 
            // btn_RemoveGroup
            // 
            this.btn_RemoveGroup.Font = new System.Drawing.Font("", 10.5F, System.Drawing.FontStyle.Regular, System.Drawing.GraphicsUnit.Point, ((byte)(134)));
            this.btn_RemoveGroup.Location = new System.Drawing.Point(577, 351);
            this.btn_RemoveGroup.Margin = new System.Windows.Forms.Padding(4);
            this.btn_RemoveGroup.Name = "btn_RemoveGroup";
            this.btn_RemoveGroup.Size = new System.Drawing.Size(140, 24);
            this.btn_RemoveGroup.TabIndex = 131;
            this.btn_RemoveGroup.Text = "Remove()";
            this.btn_RemoveGroup.UseVisualStyleBackColor = true;
            this.btn_RemoveGroup.Click += new System.EventHandler(this.btn_RemoveGroup_Click);
            // 
            // btn_ModifyGroup
            // 
            this.btn_ModifyGroup.Font = new System.Drawing.Font("", 10.5F, System.Drawing.FontStyle.Regular, System.Drawing.GraphicsUnit.Point, ((byte)(134)));
            this.btn_ModifyGroup.Location = new System.Drawing.Point(577, 283);
            this.btn_ModifyGroup.Margin = new System.Windows.Forms.Padding(4);
            this.btn_ModifyGroup.Name = "btn_ModifyGroup";
            this.btn_ModifyGroup.Size = new System.Drawing.Size(140, 24);
            this.btn_ModifyGroup.TabIndex = 130;
            this.btn_ModifyGroup.Text = "Modify()";
            this.btn_ModifyGroup.UseVisualStyleBackColor = true;
            this.btn_ModifyGroup.Click += new System.EventHandler(this.btn_ModifyGroup_Click);
            // 
            // btn_AddGroup
            // 
            this.btn_AddGroup.Font = new System.Drawing.Font("", 10.5F, System.Drawing.FontStyle.Regular, System.Drawing.GraphicsUnit.Point, ((byte)(134)));
            this.btn_AddGroup.Location = new System.Drawing.Point(577, 215);
            this.btn_AddGroup.Margin = new System.Windows.Forms.Padding(4);
            this.btn_AddGroup.Name = "btn_AddGroup";
            this.btn_AddGroup.Size = new System.Drawing.Size(140, 24);
            this.btn_AddGroup.TabIndex = 129;
            this.btn_AddGroup.Text = "Add()";
            this.btn_AddGroup.UseVisualStyleBackColor = true;
            this.btn_AddGroup.Click += new System.EventHandler(this.btn_AddGroup_Click);
            // 
            // listView_Group
            // 
            this.listView_Group.Columns.AddRange(new System.Windows.Forms.ColumnHeader[] {
            this.columnHeader2,
            this.columnHeader3,
            this.columnHeader4,
            this.columnHeader5});
            this.listView_Group.FullRowSelect = true;
            this.listView_Group.GridLines = true;
            this.listView_Group.Location = new System.Drawing.Point(6, 157);
            this.listView_Group.Name = "listView_Group";
            this.listView_Group.Size = new System.Drawing.Size(564, 312);
            this.listView_Group.TabIndex = 128;
            this.listView_Group.UseCompatibleStateImageBehavior = false;
            this.listView_Group.View = System.Windows.Forms.View.Details;
            // 
            // columnHeader2
            // 
            this.columnHeader2.Text = "No.()";
            this.columnHeader2.Width = 75;
            // 
            // columnHeader3
            // 
            this.columnHeader3.Text = "CustomName()";
            this.columnHeader3.Width = 170;
            // 
            // columnHeader4
            // 
            this.columnHeader4.Text = "StartTime()";
            this.columnHeader4.Width = 150;
            // 
            // columnHeader5
            // 
            this.columnHeader5.Text = "EndTime()";
            this.columnHeader5.Width = 150;
            // 
            // groupBox2
            // 
            this.groupBox2.Controls.Add(this.btn_Doors);
            this.groupBox2.Controls.Add(this.label2);
            this.groupBox2.Controls.Add(this.groupBox3);
            this.groupBox2.Controls.Add(this.txt_GroupNum);
            this.groupBox2.Controls.Add(this.label7);
            this.groupBox2.Controls.Add(this.btn_GetSchedule);
            this.groupBox2.Controls.Add(this.btn_SetSchedule);
            this.groupBox2.Controls.Add(this.txt_ScheduleName);
            this.groupBox2.Controls.Add(this.label4);
            this.groupBox2.Controls.Add(this.label5);
            this.groupBox2.Controls.Add(this.cmb_ScheduleGroup);
            this.groupBox2.Controls.Add(this.chb_ScheduleEnable);
            this.groupBox2.Controls.Add(this.label6);
            this.groupBox2.Location = new System.Drawing.Point(748, 12);
            this.groupBox2.Name = "groupBox2";
            this.groupBox2.Size = new System.Drawing.Size(409, 537);
            this.groupBox2.TabIndex = 134;
            this.groupBox2.TabStop = false;
            this.groupBox2.Text = "SpecialDaysSchedule()";
            // 
            // btn_Doors
            // 
            this.btn_Doors.Font = new System.Drawing.Font("", 10.5F, System.Drawing.FontStyle.Regular, System.Drawing.GraphicsUnit.Point, ((byte)(134)));
            this.btn_Doors.Location = new System.Drawing.Point(180, 446);
            this.btn_Doors.Name = "btn_Doors";
            this.btn_Doors.Size = new System.Drawing.Size(58, 21);
            this.btn_Doors.TabIndex = 138;
            this.btn_Doors.Text = "···";
            this.btn_Doors.UseVisualStyleBackColor = true;
            this.btn_Doors.Click += new System.EventHandler(this.btn_Doors_Click);
            // 
            // label2
            // 
            this.label2.AutoSize = true;
            this.label2.Location = new System.Drawing.Point(95, 449);
            this.label2.Name = "label2";
            this.label2.Size = new System.Drawing.Size(77, 14);
            this.label2.TabIndex = 137;
            this.label2.Text = "Doors():";
            // 
            // groupBox3
            // 
            this.groupBox3.Controls.Add(this.endtime4);
            this.groupBox3.Controls.Add(this.starttime4);
            this.groupBox3.Controls.Add(this.label8);
            this.groupBox3.Controls.Add(this.endtime2);
            this.groupBox3.Controls.Add(this.starttime2);
            this.groupBox3.Controls.Add(this.label9);
            this.groupBox3.Controls.Add(this.endtime3);
            this.groupBox3.Controls.Add(this.starttime3);
            this.groupBox3.Controls.Add(this.label10);
            this.groupBox3.Controls.Add(this.label12);
            this.groupBox3.Controls.Add(this.label13);
            this.groupBox3.Controls.Add(this.endtime1);
            this.groupBox3.Controls.Add(this.starttime1);
            this.groupBox3.Controls.Add(this.label14);
            this.groupBox3.Location = new System.Drawing.Point(6, 184);
            this.groupBox3.Name = "groupBox3";
            this.groupBox3.Size = new System.Drawing.Size(394, 235);
            this.groupBox3.TabIndex = 136;
            this.groupBox3.TabStop = false;
            this.groupBox3.Text = "TimeSection()";
            // 
            // endtime4
            // 
            this.endtime4.CustomFormat = "yyyy-MM-dd HH:mm:ss";
            this.endtime4.Format = System.Windows.Forms.DateTimePickerFormat.Time;
            this.endtime4.Location = new System.Drawing.Point(263, 188);
            this.endtime4.Name = "endtime4";
            this.endtime4.ShowUpDown = true;
            this.endtime4.Size = new System.Drawing.Size(118, 23);
            this.endtime4.TabIndex = 158;
            this.endtime4.Value = new System.DateTime(2020, 1, 1, 0, 0, 0, 0);
            // 
            // starttime4
            // 
            this.starttime4.CustomFormat = "yyyy-MM-dd HH:mm:ss";
            this.starttime4.Format = System.Windows.Forms.DateTimePickerFormat.Time;
            this.starttime4.Location = new System.Drawing.Point(120, 188);
            this.starttime4.Name = "starttime4";
            this.starttime4.ShowUpDown = true;
            this.starttime4.Size = new System.Drawing.Size(118, 23);
            this.starttime4.TabIndex = 157;
            this.starttime4.Value = new System.DateTime(2020, 1, 1, 0, 0, 0, 0);
            // 
            // label8
            // 
            this.label8.AutoSize = true;
            this.label8.Location = new System.Drawing.Point(13, 194);
            this.label8.Margin = new System.Windows.Forms.Padding(5, 0, 5, 0);
            this.label8.Name = "label8";
            this.label8.Size = new System.Drawing.Size(91, 14);
            this.label8.TabIndex = 156;
            this.label8.Text = "Seg4(4):";
            this.label8.TextAlign = System.Drawing.ContentAlignment.MiddleRight;
            // 
            // endtime2
            // 
            this.endtime2.CustomFormat = "yyyy-MM-dd HH:mm:ss";
            this.endtime2.Format = System.Windows.Forms.DateTimePickerFormat.Time;
            this.endtime2.Location = new System.Drawing.Point(263, 108);
            this.endtime2.Name = "endtime2";
            this.endtime2.ShowUpDown = true;
            this.endtime2.Size = new System.Drawing.Size(118, 23);
            this.endtime2.TabIndex = 155;
            this.endtime2.Value = new System.DateTime(2020, 1, 1, 0, 0, 0, 0);
            // 
            // starttime2
            // 
            this.starttime2.CustomFormat = "yyyy-MM-dd HH:mm:ss";
            this.starttime2.Format = System.Windows.Forms.DateTimePickerFormat.Time;
            this.starttime2.Location = new System.Drawing.Point(120, 108);
            this.starttime2.Name = "starttime2";
            this.starttime2.ShowUpDown = true;
            this.starttime2.Size = new System.Drawing.Size(118, 23);
            this.starttime2.TabIndex = 154;
            this.starttime2.Value = new System.DateTime(2020, 1, 1, 0, 0, 0, 0);
            // 
            // label9
            // 
            this.label9.AutoSize = true;
            this.label9.Location = new System.Drawing.Point(13, 114);
            this.label9.Margin = new System.Windows.Forms.Padding(5, 0, 5, 0);
            this.label9.Name = "label9";
            this.label9.Size = new System.Drawing.Size(91, 14);
            this.label9.TabIndex = 153;
            this.label9.Text = "Seg2(2):";
            this.label9.TextAlign = System.Drawing.ContentAlignment.MiddleRight;
            // 
            // endtime3
            // 
            this.endtime3.CustomFormat = "yyyy-MM-dd HH:mm:ss";
            this.endtime3.Format = System.Windows.Forms.DateTimePickerFormat.Time;
            this.endtime3.Location = new System.Drawing.Point(263, 148);
            this.endtime3.Name = "endtime3";
            this.endtime3.ShowUpDown = true;
            this.endtime3.Size = new System.Drawing.Size(118, 23);
            this.endtime3.TabIndex = 152;
            this.endtime3.Value = new System.DateTime(2020, 1, 1, 0, 0, 0, 0);
            // 
            // starttime3
            // 
            this.starttime3.CustomFormat = "yyyy-MM-dd HH:mm:ss";
            this.starttime3.Format = System.Windows.Forms.DateTimePickerFormat.Time;
            this.starttime3.Location = new System.Drawing.Point(120, 148);
            this.starttime3.Name = "starttime3";
            this.starttime3.ShowUpDown = true;
            this.starttime3.Size = new System.Drawing.Size(118, 23);
            this.starttime3.TabIndex = 151;
            this.starttime3.Value = new System.DateTime(2020, 1, 1, 0, 0, 0, 0);
            // 
            // label10
            // 
            this.label10.AutoSize = true;
            this.label10.Location = new System.Drawing.Point(13, 154);
            this.label10.Margin = new System.Windows.Forms.Padding(5, 0, 5, 0);
            this.label10.Name = "label10";
            this.label10.Size = new System.Drawing.Size(91, 14);
            this.label10.TabIndex = 150;
            this.label10.Text = "Seg3(3):";
            this.label10.TextAlign = System.Drawing.ContentAlignment.MiddleRight;
            // 
            // label12
            // 
            this.label12.AutoSize = true;
            this.label12.Location = new System.Drawing.Point(259, 37);
            this.label12.Margin = new System.Windows.Forms.Padding(5, 0, 5, 0);
            this.label12.Name = "label12";
            this.label12.Size = new System.Drawing.Size(126, 14);
            this.label12.TabIndex = 149;
            this.label12.Text = "EndTime()";
            this.label12.TextAlign = System.Drawing.ContentAlignment.MiddleRight;
            // 
            // label13
            // 
            this.label13.AutoSize = true;
            this.label13.Location = new System.Drawing.Point(109, 37);
            this.label13.Margin = new System.Windows.Forms.Padding(5, 0, 5, 0);
            this.label13.Name = "label13";
            this.label13.Size = new System.Drawing.Size(140, 14);
            this.label13.TabIndex = 148;
            this.label13.Text = "StartTime()";
            this.label13.TextAlign = System.Drawing.ContentAlignment.MiddleRight;
            // 
            // endtime1
            // 
            this.endtime1.CustomFormat = "yyyy-MM-dd HH:mm:ss";
            this.endtime1.Format = System.Windows.Forms.DateTimePickerFormat.Time;
            this.endtime1.Location = new System.Drawing.Point(263, 68);
            this.endtime1.Name = "endtime1";
            this.endtime1.ShowUpDown = true;
            this.endtime1.Size = new System.Drawing.Size(118, 23);
            this.endtime1.TabIndex = 147;
            this.endtime1.Value = new System.DateTime(2020, 1, 1, 0, 0, 0, 0);
            // 
            // starttime1
            // 
            this.starttime1.CustomFormat = "yyyy-MM-dd HH:mm:ss";
            this.starttime1.Format = System.Windows.Forms.DateTimePickerFormat.Time;
            this.starttime1.Location = new System.Drawing.Point(120, 68);
            this.starttime1.Name = "starttime1";
            this.starttime1.ShowUpDown = true;
            this.starttime1.Size = new System.Drawing.Size(118, 23);
            this.starttime1.TabIndex = 146;
            this.starttime1.Value = new System.DateTime(2020, 1, 1, 0, 0, 0, 0);
            // 
            // label14
            // 
            this.label14.AutoSize = true;
            this.label14.Location = new System.Drawing.Point(13, 74);
            this.label14.Margin = new System.Windows.Forms.Padding(5, 0, 5, 0);
            this.label14.Name = "label14";
            this.label14.Size = new System.Drawing.Size(91, 14);
            this.label14.TabIndex = 145;
            this.label14.Text = "Seg1(1):";
            this.label14.TextAlign = System.Drawing.ContentAlignment.MiddleRight;
            // 
            // txt_GroupNum
            // 
            this.txt_GroupNum.Location = new System.Drawing.Point(180, 141);
            this.txt_GroupNum.Margin = new System.Windows.Forms.Padding(4);
            this.txt_GroupNum.Name = "txt_GroupNum";
            this.txt_GroupNum.Size = new System.Drawing.Size(168, 23);
            this.txt_GroupNum.TabIndex = 135;
            // 
            // label7
            // 
            this.label7.AutoSize = true;
            this.label7.Location = new System.Drawing.Point(60, 144);
            this.label7.Margin = new System.Windows.Forms.Padding(4, 0, 4, 0);
            this.label7.Name = "label7";
            this.label7.Size = new System.Drawing.Size(112, 14);
            this.label7.TabIndex = 134;
            this.label7.Text = "GroupNo.():";
            // 
            // btn_GetSchedule
            // 
            this.btn_GetSchedule.Font = new System.Drawing.Font("", 10.5F, System.Drawing.FontStyle.Regular, System.Drawing.GraphicsUnit.Point, ((byte)(134)));
            this.btn_GetSchedule.Location = new System.Drawing.Point(32, 493);
            this.btn_GetSchedule.Margin = new System.Windows.Forms.Padding(4);
            this.btn_GetSchedule.Name = "btn_GetSchedule";
            this.btn_GetSchedule.Size = new System.Drawing.Size(140, 24);
            this.btn_GetSchedule.TabIndex = 133;
            this.btn_GetSchedule.Text = "Get()";
            this.btn_GetSchedule.UseVisualStyleBackColor = true;
            this.btn_GetSchedule.Click += new System.EventHandler(this.btn_GetSchedule_Click);
            // 
            // btn_SetSchedule
            // 
            this.btn_SetSchedule.Font = new System.Drawing.Font("", 10.5F, System.Drawing.FontStyle.Regular, System.Drawing.GraphicsUnit.Point, ((byte)(134)));
            this.btn_SetSchedule.Location = new System.Drawing.Point(247, 493);
            this.btn_SetSchedule.Margin = new System.Windows.Forms.Padding(4);
            this.btn_SetSchedule.Name = "btn_SetSchedule";
            this.btn_SetSchedule.Size = new System.Drawing.Size(140, 24);
            this.btn_SetSchedule.TabIndex = 132;
            this.btn_SetSchedule.Text = "Set()";
            this.btn_SetSchedule.UseVisualStyleBackColor = true;
            this.btn_SetSchedule.Click += new System.EventHandler(this.btn_SetSchedule_Click);
            // 
            // txt_ScheduleName
            // 
            this.txt_ScheduleName.Location = new System.Drawing.Point(180, 73);
            this.txt_ScheduleName.Margin = new System.Windows.Forms.Padding(4);
            this.txt_ScheduleName.MaxLength = 3000;
            this.txt_ScheduleName.Name = "txt_ScheduleName";
            this.txt_ScheduleName.Size = new System.Drawing.Size(168, 23);
            this.txt_ScheduleName.TabIndex = 127;
            // 
            // label4
            // 
            this.label4.AutoSize = true;
            this.label4.Location = new System.Drawing.Point(81, 42);
            this.label4.Margin = new System.Windows.Forms.Padding(5, 0, 5, 0);
            this.label4.Name = "label4";
            this.label4.Size = new System.Drawing.Size(91, 14);
            this.label4.TabIndex = 118;
            this.label4.Text = "Index():";
            this.label4.TextAlign = System.Drawing.ContentAlignment.MiddleRight;
            // 
            // label5
            // 
            this.label5.AutoSize = true;
            this.label5.Location = new System.Drawing.Point(74, 76);
            this.label5.Margin = new System.Windows.Forms.Padding(4, 0, 4, 0);
            this.label5.Name = "label5";
            this.label5.Size = new System.Drawing.Size(98, 14);
            this.label5.TabIndex = 126;
            this.label5.Text = "Name():";
            // 
            // cmb_ScheduleGroup
            // 
            this.cmb_ScheduleGroup.DropDownStyle = System.Windows.Forms.ComboBoxStyle.DropDownList;
            this.cmb_ScheduleGroup.FormattingEnabled = true;
            this.cmb_ScheduleGroup.Items.AddRange(new object[] {
            ""});
            this.cmb_ScheduleGroup.Location = new System.Drawing.Point(180, 39);
            this.cmb_ScheduleGroup.Name = "cmb_ScheduleGroup";
            this.cmb_ScheduleGroup.Size = new System.Drawing.Size(96, 22);
            this.cmb_ScheduleGroup.TabIndex = 119;
            this.cmb_ScheduleGroup.SelectedIndexChanged += new System.EventHandler(this.cmb_ScheduleGroup_SelectedIndexChanged);
            // 
            // chb_ScheduleEnable
            // 
            this.chb_ScheduleEnable.AutoSize = true;
            this.chb_ScheduleEnable.Font = new System.Drawing.Font("", 5.25F, System.Drawing.FontStyle.Regular, System.Drawing.GraphicsUnit.Point, ((byte)(134)));
            this.chb_ScheduleEnable.Location = new System.Drawing.Point(180, 113);
            this.chb_ScheduleEnable.Name = "chb_ScheduleEnable";
            this.chb_ScheduleEnable.Size = new System.Drawing.Size(15, 14);
            this.chb_ScheduleEnable.TabIndex = 125;
            this.chb_ScheduleEnable.UseVisualStyleBackColor = true;
            // 
            // label6
            // 
            this.label6.AutoSize = true;
            this.label6.Location = new System.Drawing.Point(74, 110);
            this.label6.Name = "label6";
            this.label6.Size = new System.Drawing.Size(98, 14);
            this.label6.TabIndex = 124;
            this.label6.Text = "Enable():";
            // 
            // HolidaySetForm
            // 
            this.AutoScaleDimensions = new System.Drawing.SizeF(7F, 14F);
            this.AutoScaleMode = System.Windows.Forms.AutoScaleMode.Font;
            this.ClientSize = new System.Drawing.Size(1163, 554);
            this.Controls.Add(this.groupBox2);
            this.Controls.Add(this.groupBox1);
            this.Font = new System.Drawing.Font("", 10.5F);
            this.FormBorderStyle = System.Windows.Forms.FormBorderStyle.FixedSingle;
            this.MaximizeBox = false;
            this.Name = "HolidaySetForm";
            this.StartPosition = System.Windows.Forms.FormStartPosition.CenterParent;
            this.Text = "HolidaySet()";
            this.Load += new System.EventHandler(this.HolidaySetForm_Load);
            this.groupBox1.ResumeLayout(false);
            this.groupBox1.PerformLayout();
            this.groupBox2.ResumeLayout(false);
            this.groupBox2.PerformLayout();
            this.groupBox3.ResumeLayout(false);
            this.groupBox3.PerformLayout();
            this.ResumeLayout(false);

        }

        #endregion
        private System.Windows.Forms.ComboBox cmb_Index;
        private System.Windows.Forms.Label label1;
        private System.Windows.Forms.CheckBox chb_Enable;
        private System.Windows.Forms.Label label11;
        private System.Windows.Forms.TextBox txt_Name;
        private System.Windows.Forms.Label label3;
        private System.Windows.Forms.GroupBox groupBox1;
        private System.Windows.Forms.Button btn_GetGroup;
        private System.Windows.Forms.Button btn_SetGroup;
        private System.Windows.Forms.Button btn_RemoveGroup;
        private System.Windows.Forms.Button btn_ModifyGroup;
        private System.Windows.Forms.Button btn_AddGroup;
        private System.Windows.Forms.ListView listView_Group;
        private System.Windows.Forms.ColumnHeader columnHeader2;
        private System.Windows.Forms.ColumnHeader columnHeader3;
        private System.Windows.Forms.ColumnHeader columnHeader4;
        private System.Windows.Forms.ColumnHeader columnHeader5;
        private System.Windows.Forms.GroupBox groupBox2;
        private System.Windows.Forms.TextBox txt_GroupNum;
        private System.Windows.Forms.Label label7;
        private System.Windows.Forms.Button btn_GetSchedule;
        private System.Windows.Forms.Button btn_SetSchedule;
        private System.Windows.Forms.TextBox txt_ScheduleName;
        private System.Windows.Forms.Label label4;
        private System.Windows.Forms.Label label5;
        private System.Windows.Forms.ComboBox cmb_ScheduleGroup;
        private System.Windows.Forms.CheckBox chb_ScheduleEnable;
        private System.Windows.Forms.Label label6;
        private System.Windows.Forms.GroupBox groupBox3;
        private System.Windows.Forms.DateTimePicker endtime4;
        private System.Windows.Forms.DateTimePicker starttime4;
        private System.Windows.Forms.Label label8;
        private System.Windows.Forms.DateTimePicker endtime2;
        private System.Windows.Forms.DateTimePicker starttime2;
        private System.Windows.Forms.Label label9;
        private System.Windows.Forms.DateTimePicker endtime3;
        private System.Windows.Forms.DateTimePicker starttime3;
        private System.Windows.Forms.Label label10;
        private System.Windows.Forms.Label label12;
        private System.Windows.Forms.Label label13;
        private System.Windows.Forms.DateTimePicker endtime1;
        private System.Windows.Forms.DateTimePicker starttime1;
        private System.Windows.Forms.Label label14;
        private System.Windows.Forms.Button btn_Doors;
        private System.Windows.Forms.Label label2;
    }
}